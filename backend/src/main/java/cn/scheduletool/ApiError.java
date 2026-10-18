package cn.scheduletool;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.*;

@RestControllerAdvice
public class ApiError {
    private static final Logger LOG=LoggerFactory.getLogger(ApiError.class);
    public static class Failure extends RuntimeException {
        public final int status;
        public Failure(int status, String message) { super(message); this.status = status; }
    }
    @ExceptionHandler(Failure.class)
    ProblemDetail failure(Failure e) { return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status), e.getMessage()); }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ProblemDetail badInput(Exception e) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求格式或时间参数不正确"); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        var error = e.getBindingResult().getFieldError();
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error == null ? "请检查填写内容" : error.getDefaultMessage());
    }
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        if(e instanceof org.springframework.web.ErrorResponse error && error.getStatusCode().is4xxClientError())
            return ProblemDetail.forStatusAndDetail(error.getStatusCode(),"请求路径、参数或方法不正确");
        // Do not include SQL, request bodies or credentials in an error response/log.
        String locations=java.util.Arrays.stream(e.getStackTrace()).filter(f->f.getClassName().startsWith("cn.scheduletool."))
            .limit(6).map(StackTraceElement::toString).collect(java.util.stream.Collectors.joining(" <- "));
        LOG.error("request failure type={} locations={}",e.getClass().getName(),locations);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,"操作失败，请提供响应头中的 X-Request-ID 排查");
    }
}
