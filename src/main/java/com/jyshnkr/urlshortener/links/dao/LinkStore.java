package com.jyshnkr.urlshortener.links.dao;

import com.jyshnkr.urlshortener.links.exception.CreationFailure;
import com.jyshnkr.urlshortener.links.model.Link;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class LinkStore {

  private final JdbcTemplate jdbc;

  public LinkStore(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(3);
  }

  public Optional<Link> findDestination(String destinationUrl) {
    try {
      var candidate = jdbc.query("""
          SELECT short_code, short_url, destination_url FROM short_links
          WHERE destination_hash = sha256(convert_to(?, 'UTF8'))
          """,
          (row, index) -> new Link(
              row.getString("short_code"), row.getString("short_url"), row.getString("destination_url")),
          destinationUrl).stream().findFirst();
      // A fingerprint is only an index key. Never reuse a different full destination.
      if (candidate.isPresent() && !candidate.get().destinationUrl().equals(destinationUrl)) {
        throw unavailable();
      }
      return candidate;
    } catch (DataAccessException exception) {
      throw unavailable();
    }
  }

  public boolean insertIfAvailable(Link result) {
    try {
      // Autocommit makes the insert durable before returning. ON CONFLICT waits for its
      // winner; the subsequent SELECT uses a fresh snapshot to see that committed row.
      return jdbc.update("""
          INSERT INTO short_links
            (short_code, destination_url, destination_hash, short_url, created_at)
          VALUES (?, ?, sha256(convert_to(?, 'UTF8')), ?, CURRENT_TIMESTAMP)
          ON CONFLICT DO NOTHING
          """,
          result.code(), result.destinationUrl(), result.destinationUrl(), result.shortUrl()) == 1;
    } catch (DataAccessException exception) {
      throw unavailable();
    }
  }

  private static CreationFailure unavailable() {
    return new CreationFailure(CreationFailure.Reason.UNAVAILABLE,
        "Link storage is temporarily unavailable. Retry with the same destinationUrl.");
  }
}
