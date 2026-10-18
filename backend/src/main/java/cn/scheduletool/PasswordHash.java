package cn.scheduletool;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.charset.StandardCharsets;
public class PasswordHash {
    public static void main(String[] args) throws Exception {
        String password=new String(System.in.readAllBytes(),StandardCharsets.UTF_8).strip();
        if(password.length()<12) throw new IllegalArgumentException("密码至少 12 位");
        System.out.print(new BCryptPasswordEncoder(12).encode(password));
    }
}
