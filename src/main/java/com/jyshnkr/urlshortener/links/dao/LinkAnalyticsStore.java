package com.jyshnkr.urlshortener.links.dao;

import com.jyshnkr.urlshortener.links.exception.LinkFailure;
import com.jyshnkr.urlshortener.links.model.LinkStats;
import com.jyshnkr.urlshortener.links.model.RedirectEvent;
import com.jyshnkr.urlshortener.links.model.RedirectIncrement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionTemplate;

public final class LinkAnalyticsStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public void increment(List<RedirectIncrement> increments) {
    if (increments.isEmpty()) {
      return;
    }
    jdbc.update(
        """
        INSERT INTO link_analytics (short_code, redirect_count, last_redirected_at)
        SELECT * FROM unnest(?::text[], ?::bigint[], ?::timestamptz[])
        ON CONFLICT (short_code) DO UPDATE SET
          redirect_count = link_analytics.redirect_count + EXCLUDED.redirect_count,
          last_redirected_at = GREATEST(link_analytics.last_redirected_at, EXCLUDED.last_redirected_at)
        """,
        new SqlArrayValue("text", increments.stream().map(RedirectIncrement::code).toArray()),
        new SqlArrayValue("int8", increments.stream().map(RedirectIncrement::count).toArray()),
        new SqlArrayValue(
            "timestamptz",
            increments.stream()
                .map(increment -> increment.lastRedirectedAt().toString())
                .toArray()));
  }

  /**
   * Counts each event ID at most once. Saving the IDs and the counters in one transaction means a
   * redelivered event is skipped, and a failed call saves neither.
   *
   * @return the number of events counted by this call
   */
  public int incrementNewEvents(List<RedirectEvent> events) {
    // Sorted, distinct IDs give simultaneous writers a consistent unique-index lock order.
    var byId = new TreeMap<UUID, RedirectEvent>();
    events.forEach(event -> byId.putIfAbsent(event.eventId(), event));
    if (byId.isEmpty()) {
      return 0;
    }
    var counted = new ArrayList<RedirectEvent>();
    transactions.executeWithoutResult(
        status -> {
          counted.clear();
          for (String id :
              jdbc.queryForList(
                  """
                  INSERT INTO processed_redirect_events (event_id)
                  SELECT * FROM unnest(?::uuid[])
                  ON CONFLICT (event_id) DO NOTHING
                  RETURNING event_id::text
                  """,
                  String.class,
                  new SqlArrayValue(
                      "uuid", byId.keySet().stream().map(UUID::toString).toArray()))) {
            counted.add(byId.get(UUID.fromString(id)));
          }
          increment(
              RedirectIncrement.combine(
                  counted.stream()
                      .map(event -> new RedirectIncrement(event.code(), 1, event.redirectedAt()))
                      .toList()));
        });
    return counted.size();
  }

  public LinkAnalyticsStore(DataSource dataSource) {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.setQueryTimeout(3);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  public Optional<LinkStats> findByCode(String code) {
    try {
      return jdbc
          .query(
              """
          SELECT l.short_code, COALESCE(a.redirect_count, 0) AS redirect_count, a.last_redirected_at
          FROM short_links l LEFT JOIN link_analytics a USING (short_code)
          WHERE l.short_code = ?
          """,
              (row, index) -> {
                var timestamp = row.getTimestamp("last_redirected_at");
                return new LinkStats(
                    row.getString("short_code"),
                    row.getLong("redirect_count"),
                    timestamp == null ? null : timestamp.toInstant());
              },
              code)
          .stream()
          .findFirst();
    } catch (DataAccessException failure) {
      throw new LinkFailure(
          LinkFailure.Reason.UNAVAILABLE,
          "Link statistics are temporarily unavailable. Retry the same request.");
    }
  }
}
