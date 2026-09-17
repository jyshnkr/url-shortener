package com.jyshnkr.urlshortener.links.dao;

import com.jyshnkr.urlshortener.links.exception.LinkFailure;
import com.jyshnkr.urlshortener.links.model.LinkStats;
import com.jyshnkr.urlshortener.links.model.RedirectIncrement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;

public final class LinkAnalyticsStore {
  private final JdbcTemplate jdbc;

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

  public LinkAnalyticsStore(DataSource dataSource) {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.setQueryTimeout(3);
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
