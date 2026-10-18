package cn.scheduletool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="app.mcp.enabled",havingValue="true")
class McpTools {
    private final McpApi api;
    private final ObjectMapper json;
    McpTools(McpApi api,ObjectMapper json) { this.api=api; this.json=json; }
    private static Map<String,Object> string(String description) { return Map.of("type","string","description",description); }
    private static Map<String,Object> integer(String description,int minimum,int maximum) { return Map.of("type","integer","description",description,"minimum",minimum,"maximum",maximum); }
    private static Map<String,Object> choices(String description,String... values) { return Map.of("type","string","description",description,"enum",List.of(values)); }
    private static final Map<String,Object> SOURCE=choices("执行 AI 自报来源；ChatGPT 填 CHATGPT，Codex 填 CODEX，不确定填 UNKNOWN。标签不证明客户端身份。","CHATGPT","CODEX","OTHER","UNKNOWN");
    private static final Map<String,Object> ID=string("查询得到的 seriesId，UUID；不能使用 seriesId@日期 的展示 ID。");
    private static final Map<String,Object> SCOPE=choices("OCCURRENCE 操作某一次，SERIES 操作整个系列。用户未明确范围时先澄清。","SERIES","OCCURRENCE");
    private static final Map<String,Object> DATE=string("原重复规则日期 YYYY-MM-DD，即使该次已经移动也不变。OCCURRENCE 必填。");
    private static Map<String,Object> event(boolean update) {
        var fields=new LinkedHashMap<String,Object>();
        fields.put("title",Map.of("type","string","minLength",1,"maxLength",200));
        fields.put("description",Map.of("type",List.of("string","null"),"maxLength",4000));
        fields.put("start",string("Asia/Shanghai 本地时间 YYYY-MM-DDTHH:mm:ss，不带 UTC 偏移；全天为零点。"));
        fields.put("end",Map.of("type",List.of("string","null"),"description","时间点填 null；时间段结束必须晚于开始；全天结束为最后一天之后的零点。"));
        fields.put("allDay",Map.of("type","boolean","default",false));
        fields.put("timeZone",choices("时区固定 Asia/Shanghai。","Asia/Shanghai"));
        fields.put("recurrence",choices("无重复、每天或每周。","NONE","DAILY","WEEKLY"));
        fields.put("repeatUntil",Map.of("type",List.of("string","null"),"description","重复截止日期 YYYY-MM-DD，包含该日；null 表示无截止。"));
        fields.put("color",Map.of("type","string","pattern","^#[0-9a-fA-F]{6}$"));
        if(update) fields.put("version",integer("先查询，再使用返回的系列版本；冲突后重新查询，不能盲重试。",0,Integer.MAX_VALUE));
        return Map.of("type","object","properties",fields,"required",update?List.of("title","start","version"):List.of("title","start"),"additionalProperties",false);
    }
    List<SyncToolSpecification> definitions() {
        return List.of(
            define("schedule_status","查看连接状态、当前上海时间和默认时区，用于解释相对日期。",Map.of(),List.of(),false),
            define("schedule_list","按左闭右开时间范围查询展开日程，最多 366 天；修改前先查询 seriesId、version 和原 occurrenceDate。",
                Map.of("from",string("开始本地时间 YYYY-MM-DDTHH:mm:ss"),"to",string("结束本地时间 YYYY-MM-DDTHH:mm:ss，不包含该时刻")),List.of("from","to"),false),
            define("schedule_get","读取一个日程系列的定义和最新版本。",Map.of("seriesId",ID),List.of("seriesId"),false),
            define("schedule_create","新增时间点、时间段、全天、跨天、每天或每周日程。仅明确时间点时 end=null，不补造时长。写入超时后先核对审计和日程。",
                Map.of("event",event(false),"source",SOURCE),List.of("event","source"),true),
            define("schedule_update","修改某一次或整个系列。SERIES 会清除全部单次例外和取消，须向用户说明；先查询并填写最新 version。409 或超时不能自动重试。",
                Map.of("seriesId",ID,"scope",SCOPE,"occurrenceDate",DATE,"event",event(true),"source",SOURCE),List.of("seriesId","scope","event","source"),true),
            define("schedule_delete","删除某一次或整个系列；必须使用先前查询的版本，单次操作填原规则日期。409 或超时后先核对，不自动重试。",
                Map.of("seriesId",ID,"scope",SCOPE,"occurrenceDate",DATE,"version",integer("最新查询到的系列版本。",0,Integer.MAX_VALUE),"source",SOURCE),List.of("seriesId","scope","version","source"),true),
            define("schedule_history","查询服务自动生成的日程审计记录；可按系列或 requestId 回溯，序号分页。",
                Map.of("seriesId",ID,"requestId",string("请求 ID"),"after",integer("起始序号，不包含，默认 0。",0,Integer.MAX_VALUE),"limit",integer("最多条数，默认 20。",1,200)),List.of(),false),
            define("schedule_verify","校验服务器审计哈希链；链内一致不代表第三方存证，库外校验点由本机 skill 管理。",Map.of(),List.of(),false));
    }
    private SyncToolSpecification define(String name,String description,Map<String,Object> properties,List<String> required,boolean write) {
        var scopes=write?List.of(McpOAuth.READ,McpOAuth.WRITE):List.of(McpOAuth.READ);
        var tool=McpSchema.Tool.builder().name(name).description(description)
            .inputSchema(new McpSchema.JsonSchema("object",properties,required,false,null,null))
            .annotations(new McpSchema.ToolAnnotations(null,!write,write,!write,false,false))
            .meta(Map.of("securitySchemes",List.of(Map.of("type","oauth2","scopes",scopes)))).build();
        return new SyncToolSpecification(tool,(context,request)->{
            try {
                var principal=(Jwt)context.get("principal");
                Object scopeClaim=principal==null?null:principal.getClaim("scope");
                Collection<?> granted=scopeClaim instanceof Collection<?> c?c:scopeClaim instanceof String s?List.of(s.split(" ")):List.of();
                if(!granted.containsAll(scopes)) throw new ApiError.Failure(403,"当前授权缺少 "+String.join(" ",scopes));
                var args=request.arguments()==null?Map.<String,Object>of():request.arguments();
                if(!properties.keySet().containsAll(args.keySet()) || !args.keySet().containsAll(required))
                    throw new ApiError.Failure(400,"工具参数缺失或包含未知字段");
                var result=execute(name,args);
                return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(json.writeValueAsString(result)))).structuredContent(result).isError(false).build();
            } catch(ApiError.Failure failure) {
                return error(failure.status,failure.getMessage());
            } catch(IllegalArgumentException|ClassCastException failure) { return error(400,"参数格式或日期不正确，请核对工具说明"); }
            catch(Exception failure) { return error(500,"工具结果未确认；先核对日程和审计，不直接重试写入"); }
        });
    }
    private McpSchema.CallToolResult error(int status,String detail) {
        return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(detail))).structuredContent(Map.of("status",status,"detail",detail)).isError(true).build();
    }
    private Object execute(String name,Map<String,Object> args) {
        boolean write=Set.of("schedule_create","schedule_update","schedule_delete").contains(name);
        String source=write?(args.get("source") instanceof String value?value:""):"UNKNOWN";
        if(write && !Set.of("CODEX","CHATGPT","OTHER","UNKNOWN").contains(source)) throw new ApiError.Failure(400,"来源标签不正确");
        String path="/api/v1/events",method="GET"; Object body=null;
        if(Set.of("schedule_get","schedule_update","schedule_delete").contains(name)) path+="/"+UUID.fromString((String)args.get("seriesId"));
        if(name.equals("schedule_list")) {
            var from=LocalDateTime.parse((String)args.get("from")); var to=LocalDateTime.parse((String)args.get("to"));
            if(!to.isAfter(from) || Duration.between(from,to).compareTo(Duration.ofDays(366))>0) throw new ApiError.Failure(400,"查询范围须大于 0 且不超过 366 天");
            path+="?from="+encode(from.toString())+"&to="+encode(to.toString());
        }
        if(write && !name.equals("schedule_create")) {
            String scope=(String)args.get("scope");
            if(!Set.of("SERIES","OCCURRENCE").contains(scope)) throw new ApiError.Failure(400,"请明确操作某一次还是整个系列");
            path+="?scope="+scope;
            if("OCCURRENCE".equals(scope)) {
                if(!args.containsKey("occurrenceDate")) throw new ApiError.Failure(400,"单次操作必须提供原规则日期 occurrenceDate");
                path+="&occurrenceDate="+LocalDate.parse((String)args.get("occurrenceDate"));
            } else if(args.containsKey("occurrenceDate")) throw new ApiError.Failure(400,"整系列操作不接收 occurrenceDate");
        }
        if(name.equals("schedule_create") || name.equals("schedule_update")) {
            if(!(args.get("event") instanceof Map<?,?> event)) throw new ApiError.Failure(400,"event 必须是日程对象");
            var eventBody=new LinkedHashMap<String,Object>(); event.forEach((k,v)->eventBody.put((String)k,v));
            @SuppressWarnings("unchecked") var fields=(Map<String,Object>)event(name.equals("schedule_update")).get("properties");
            if(!fields.keySet().containsAll(eventBody.keySet())) throw new ApiError.Failure(400,"日程包含未知字段");
            if(name.equals("schedule_create")) eventBody.put("version",0);
            body=eventBody; method=name.equals("schedule_create")?"POST":"PUT";
        }
        if(name.equals("schedule_delete")) { method="DELETE"; path+="&version="+number(args,"version",0,Integer.MAX_VALUE); }
        if(name.equals("schedule_history")) {
            path="/api/v1/audit-logs?after="+number(args,"after",0,Integer.MAX_VALUE)+"&limit="+(args.containsKey("limit")?number(args,"limit",1,200):20);
            if(args.containsKey("seriesId")) path+="&seriesId="+UUID.fromString((String)args.get("seriesId"));
            if(args.containsKey("requestId")) path+="&requestId="+UUID.fromString((String)args.get("requestId"));
        }
        if(name.equals("schedule_verify")) path="/api/v1/audit-logs/verify";
        if(name.equals("schedule_status")) path="/api/v1/auth/me";
        final String target=path,verb=method; final Object input=body;
        return api.session(session->{
            if(write) {
                var verification=(com.fasterxml.jackson.databind.JsonNode)session.call("GET","/api/v1/audit-logs/verify",null,"UNKNOWN").result();
                if(!verification.path("valid").asBoolean()) throw new ApiError.Failure(409,"审计链校验失败，停止写入");
            }
            var reply=session.call(verb,target,input,source);
            if(name.equals("schedule_status")) return Map.of("username",((com.fasterxml.jackson.databind.JsonNode)reply.result()).path("username").asText(),
                "timeZone","Asia/Shanghai","serverTime",ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
            var result=new LinkedHashMap<String,Object>(); result.put("result",reply.result()); result.put("requestId",reply.requestId());
            if(write) { result.put("auditSequence",reply.auditSequence()); result.put("declaredSource",source); }
            return result;
        });
    }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private static long number(Map<String,Object> args,String key,int min,int max) {
        Object value=args.getOrDefault(key,0);
        if(!(value instanceof Number number) || number.doubleValue()!=number.longValue() || number.longValue()<min || number.longValue()>max)
            throw new ApiError.Failure(400,key+" 的整数范围不正确");
        return number.longValue();
    }
}
