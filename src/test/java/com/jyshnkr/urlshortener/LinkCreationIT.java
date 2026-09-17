package com.jyshnkr.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jyshnkr.urlshortener.links.config.LinksSettings;
import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.exception.LinkFailure;
import com.jyshnkr.urlshortener.links.model.CreationOutcome;
import com.jyshnkr.urlshortener.links.service.LinkCreationService;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfig.class)
class LinkCreationIT {

  @Autowired private DataSource dataSource;

  @Test
  void simultaneousDifferentDestinationsRemainIndependent() throws Exception {
    var barrier = new CyclicBarrier(2);
    var candidates = new AtomicInteger();
    var links =
        new LinkCreationService(
            new LinkStore(dataSource),
            new LinksSettings("https://short.example"),
            () -> {
              int candidate = candidates.getAndIncrement();
              awaitBothCallers(barrier);
              return candidate == 0 ? "RaceDiff01" : "RaceDiff02";
            });
    try (var callers = Executors.newFixedThreadPool(2)) {
      var first = callers.submit(() -> links.create("https://example.com/first"));
      var second = callers.submit(() -> links.create("https://example.com/second"));
      var firstResult = first.get(10, TimeUnit.SECONDS);
      var secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(firstResult.created()).isTrue();
      assertThat(secondResult.created()).isTrue();
      assertThat(firstResult.link().code()).isNotEqualTo(secondResult.link().code());
    }
  }

  @Test
  void repeatedCodeCollisionsStopAfterFiveAttemptsAndLeaveTheRequestRetryable() {
    new LinkCreationService(
            new LinkStore(dataSource),
            new LinksSettings("https://short.example"),
            () -> "TakenCode1")
        .create("https://example.com/taken");
    var attempts = new AtomicInteger();
    var collisions =
        new LinkCreationService(
            new LinkStore(dataSource),
            new LinksSettings("https://short.example"),
            () -> {
              attempts.incrementAndGet();
              return "TakenCode1";
            });

    assertThatThrownBy(() -> collisions.create("https://example.com/retry"))
        .isInstanceOfSatisfying(
            LinkFailure.class,
            failure -> assertThat(failure.reason()).isEqualTo(LinkFailure.Reason.UNAVAILABLE));
    assertThat(attempts.get()).isEqualTo(5);

    var recovered =
        new LinkCreationService(
                new LinkStore(dataSource),
                new LinksSettings("https://short.example"),
                () -> "RetryCode1")
            .create("https://example.com/retry");
    assertThat(recovered.link().code()).isEqualTo("RetryCode1");
    assertThat(collisions.create(recovered.link().destinationUrl()))
        .isEqualTo(new CreationOutcome(recovered.link(), false));
    assertThat(attempts.get()).isEqualTo(5);
  }

  @Test
  void theFifthCandidateCanSucceed() {
    new LinkCreationService(
            new LinkStore(dataSource),
            new LinksSettings("https://short.example"),
            () -> "TakenCode2")
        .create("https://example.com/occupied");
    var attempts = new AtomicInteger();
    var links =
        new LinkCreationService(
            new LinkStore(dataSource),
            new LinksSettings("https://short.example"),
            () -> attempts.incrementAndGet() < 5 ? "TakenCode2" : "FifthCode1");

    assertThat(links.create("https://example.com/fifth").link().code()).isEqualTo("FifthCode1");
    assertThat(attempts.get()).isEqualTo(5);
  }

  @Test
  void aCodeCollisionRetriesWithoutOverwritingTheExistingLink() {
    var originalService =
        new LinkCreationService(
            new LinkStore(dataSource),
            new LinksSettings("https://short.example"),
            () -> "ClashCode1");
    var original = originalService.create("https://example.com/original");
    var attempts = new AtomicInteger();
    var links =
        new LinkCreationService(
            new LinkStore(dataSource),
            new LinksSettings("https://short.example"),
            () -> attempts.incrementAndGet() == 1 ? "ClashCode1" : "FreshCode1");

    var created = links.create("https://example.com/new");

    assertThat(created.link().code()).isEqualTo("FreshCode1");
    assertThat(attempts.get()).isEqualTo(2);
    assertThat(originalService.create(original.link().destinationUrl()))
        .isEqualTo(new CreationOutcome(original.link(), false));
  }

  @Test
  void simultaneousMatchingCreationsConvergeOnOneCommittedResult() throws Exception {
    var barrier = new CyclicBarrier(2);
    var candidates = new AtomicInteger();
    var links =
        new LinkCreationService(
            new LinkStore(dataSource),
            new LinksSettings("https://short.example"),
            () -> {
              int candidate = candidates.getAndIncrement();
              awaitBothCallers(barrier);
              return candidate == 0 ? "RaceCode01" : "RaceCode02";
            });

    try (var callers = Executors.newFixedThreadPool(2)) {
      var first = callers.submit(() -> links.create("https://example.com/race"));
      var second = callers.submit(() -> links.create("https://example.com/race"));
      var original = first.get(10, TimeUnit.SECONDS);
      var other = second.get(10, TimeUnit.SECONDS);
      assertThat(other.link()).isEqualTo(original.link());
      assertThat(List.of(original, other).stream().filter(CreationOutcome::created).count())
          .isEqualTo(1);
      assertThat(links.create("https://example.com/race"))
          .isEqualTo(new CreationOutcome(original.link(), false));
    }
  }

  private static void awaitBothCallers(CyclicBarrier barrier) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
    } catch (Exception exception) {
      throw new AssertionError("Both creation attempts must reach code generation", exception);
    }
  }
}
