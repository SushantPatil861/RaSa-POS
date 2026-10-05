package com.cafepos.documents;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Plain-English message when an upload is over the size limit (otherwise it would show as "Unexpected error"). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadErrors {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "The file is too large (the limit is 15 MB)");
    }
}
