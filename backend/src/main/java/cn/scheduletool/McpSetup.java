package cn.scheduletool;

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Explicit offline setup; never prints or overwrites secrets. */
class McpSetup {
    static void create(Path directory) throws Exception {
        Files.createDirectory(directory);
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        write(directory.resolve("client-secret.txt"),secret+"\n");
        write(directory.resolve("client-secret.hash"),new BCryptPasswordEncoder(12).encode(secret)+"\n");
        write(directory.resolve("signing.jwk"),new RSAKeyGenerator(3072).keyID(UUID.randomUUID().toString()).generate().toJSONString()+"\n");
        System.out.println("MCP 私密文件已创建，请限制目录读取权限；未输出密钥。");
    }
    private static void write(Path path,String text) throws Exception {
        Files.writeString(path,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
}
