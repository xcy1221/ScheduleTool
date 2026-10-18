package cn.scheduletool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ScheduleApplication {
    public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("--hash-password")) { PasswordHash.main(args); return; }
        if(args.length==2 && args[0].equals("--prepare-mcp")) { McpSetup.create(java.nio.file.Path.of(args[1])); return; }
        SpringApplication.run(ScheduleApplication.class, args);
    }
}
