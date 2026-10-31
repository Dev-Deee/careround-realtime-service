package com.dayo.careround.realtimeservice.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerNotFoundTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unmappedPathIs404WithTheMethodAndPath() {
        ResponseEntity<Map<String, Object>> r = handler.handleNoResource(new NoResourceFoundException(HttpMethod.POST, "/api/v1/x/", "api/v1/x"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("status", 404).containsEntry("error", "Not Found")
                .containsEntry("message", "No endpoint POST /api/v1/x").containsKey("timestamp");
    }

    @Test
    void wrongMethodIs405() {
        assertThat(handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("DELETE")).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void unsupportedMediaTypeIs415() {
        assertThat(handler.handleMediaTypeNotSupported(new HttpMediaTypeNotSupportedException("text/plain")).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void malformedJsonIs400() {
        assertThat(handler.handleNotReadable(new HttpMessageNotReadableException("bad", new MockHttpInputMessage(new byte[0]))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nonUuidIdIs400() throws Exception {
        MethodParameter param = new MethodParameter(GlobalExceptionHandlerNotFoundTest.class.getDeclaredMethod("sample", UUID.class), 0);
        assertThat(handler.handleTypeMismatch(new MethodArgumentTypeMismatchException("not-a-uuid", UUID.class, "id", param, new IllegalArgumentException()))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unexpectedErrorIsGeneric500WithoutDetail() {
        ResponseEntity<Map<String, Object>> r = handler.handleGeneral(new IllegalStateException("internal detail"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).containsEntry("message", "An unexpected error occurred");
        assertThat(r.getBody().toString()).doesNotContain("internal detail");
    }

    @SuppressWarnings("unused")
    private void sample(UUID id) { }
}
