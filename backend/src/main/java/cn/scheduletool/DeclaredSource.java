package cn.scheduletool;

final class DeclaredSource {
    static final String PATTERN="^[!-~]([ -~]*[!-~])?$";
    static String require(String value) {
        if(value==null || value.isEmpty() || value.length()>64 || !value.matches(PATTERN))
            throw new ApiError.Failure(400,"来源须为 1–64 位可打印 ASCII，首尾不能有空格；请填写执行 agent 的身份");
        return value;
    }
    private DeclaredSource() {}
}
