package com.jyshnkr.urlshortener;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Test-only loopback relay that can silently discard requests to its disposable database. */
final class StallingDatabaseProxy implements AutoCloseable {

  private final ServerSocket listener;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
  private volatile boolean stalled;

  StallingDatabaseProxy(String databaseHost, int databasePort) throws IOException {
    listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    workers.submit(() -> {
      while (!listener.isClosed()) {
        try {
          Socket client = listener.accept();
          sockets.add(client);
          Socket database = new Socket();
          sockets.add(database);
          database.connect(new InetSocketAddress(databaseHost, databasePort), 2000);
          workers.submit(() -> relay(client, database, true));
          workers.submit(() -> relay(database, client, false));
        } catch (IOException exception) {
          if (!listener.isClosed()) {
            throw new IllegalStateException("Test database relay failed", exception);
          }
        }
      }
    });
  }

  int port() {
    return listener.getLocalPort();
  }

  void stall() {
    stalled = true;
  }

  private void relay(Socket source, Socket target, boolean towardDatabase) {
    try {
      var input = source.getInputStream();
      var output = target.getOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (!(towardDatabase && stalled)) {
          output.write(buffer, 0, count);
          output.flush();
        }
      }
    } catch (IOException ignored) {
      // Expected when the driver times out or the test closes its sockets.
    } finally {
      closeSocket(source);
      closeSocket(target);
    }
  }

  private static void closeSocket(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // Best-effort cleanup after the connection is already unusable.
    }
  }

  @Override
  public void close() throws IOException {
    listener.close();
    sockets.forEach(StallingDatabaseProxy::closeSocket);
    workers.shutdownNow();
  }
}
