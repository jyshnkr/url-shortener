package com.jyshnkr.urlshortener.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jyshnkr.urlshortener.links.config.LinksSettings;
import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.exception.CreationFailure;
import com.jyshnkr.urlshortener.links.service.LinkCreationService;
import java.sql.SQLException;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LinkCreationTest {

  private final LinkCreationService links = new LinkCreationService(
      new LinkStore(mock(DataSource.class)), new LinksSettings("https://short.example"), () -> "UnusedCode");

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"/relative", "ftp://short.example", "https://user:secret@short.example",
      "https://short.example?redirect=elsewhere", "https://short.example#fragment"})
  void anInvalidTrustedBaseUrlFailsAtConfigurationTime(String baseUrl) {
    assertThatThrownBy(() -> new LinksSettings(baseUrl))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shortener.base-url");
  }

  @Test
  void unavailableStorageProducesASafeRetryableFailure() throws Exception {
    var unavailable = mock(DataSource.class);
    when(unavailable.getConnection()).thenThrow(new SQLException("private-database-detail"));
    var service = new LinkCreationService(new LinkStore(unavailable), new LinksSettings("https://short.example"), () -> "UnusedCode");

    assertThatThrownBy(() -> service.create("https://example.com"))
        .isInstanceOfSatisfying(CreationFailure.class, failure -> {
          assertThat(failure.reason()).isEqualTo(CreationFailure.Reason.UNAVAILABLE);
          assertThat(failure.getMessage()).doesNotContain("private-database-detail", "SQLException");
        });
  }

  @ParameterizedTest
  @MethodSource("invalidDestinations")
  void invalidDestinationsFailBeforeAccessingStorage(String destination) {
    assertThatThrownBy(() -> links.create(destination))
        .isInstanceOfSatisfying(CreationFailure.class,
            failure -> assertThat(failure.reason())
                .isEqualTo(CreationFailure.Reason.INVALID_INPUT));
  }

  private static Stream<String> invalidDestinations() {
    return Stream.of(null, "", " ", "/relative", "example.com", "ftp://example.com/file",
        "javascript:alert(1)", "https:///path", "https://user:password@example.com",
        "https://example.com/a b", "https://example.com/\n", "https://example.com/\u0000",
        "https://example.com/\u00a0", "https://example.com/%zz", "https://example.com:65536",
        "https://example.com/" + "a".repeat(2030));
  }
}
