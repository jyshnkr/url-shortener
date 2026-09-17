package com.jyshnkr.urlshortener.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.exception.LinkFailure;
import com.jyshnkr.urlshortener.links.service.LinkResolutionService;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LinkResolutionTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "short",
        "123456789",
        "12345678901",
        "Abc_def123",
        "Abc-def123",
        "Abcdef123é",
        "Ａbcdef1234",
        "Abcdef123\n",
        "Abcdef1234\n",
        " Abcdef1234",
        "Abcdef1234 "
      })
  void malformedCodesAreNotFoundWithoutAccessingStorage(String code) {
    var database = mock(DataSource.class);
    var resolution = new LinkResolutionService(new LinkStore(database));

    assertThatThrownBy(() -> resolution.resolve(code))
        .isInstanceOfSatisfying(
            LinkFailure.class,
            failure -> {
              assertThat(failure.reason()).isEqualTo(LinkFailure.Reason.NOT_FOUND);
              assertThat(failure.getMessage()).isEqualTo("Short link not found.");
            });
    verifyNoInteractions(database);
  }
}
