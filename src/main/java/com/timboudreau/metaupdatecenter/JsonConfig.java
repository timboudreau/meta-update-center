package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.jackson.JacksonConfigurer;
import com.mastfrog.util.time.TimeUtil;
import com.timboudreau.metaupdatecenter.borrowed.SpecificationVersion;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.regex.Pattern;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = JacksonConfigurer.class)
public class JsonConfig implements JacksonConfigurer {

    @Override
    public ObjectMapper configure(ObjectMapper mapper) {
        SimpleModule sm = new SimpleModule("specversion", new Version(1, 0, 0, null, "org.netbeans.modules", "SpecificationVersion"));
        // For logging purposes, iso dates are more useful
        sm.addSerializer(new SpecVersionSerializer());
        sm.addSerializer(new DateTimeSerializer());
        sm.addDeserializer(ZonedDateTime.class, new DateTimeDeserializer());
        mapper.registerModule(sm);
        return mapper;
    }

    private static class SpecVersionSerializer extends JsonSerializer<SpecificationVersion> {

        @Override
        public Class<SpecificationVersion> handledType() {
            return SpecificationVersion.class;
        }

        @Override
        public void serialize(SpecificationVersion t, JsonGenerator jg, SerializerProvider sp) throws IOException, JsonProcessingException {
            jg.writeString(t.toString());
        }
    }

    private static class DateTimeSerializer extends JsonSerializer<ZonedDateTime> {

        @Override
        public Class<ZonedDateTime> handledType() {
            return ZonedDateTime.class;
        }

        @Override
        public void serialize(ZonedDateTime t, JsonGenerator jg, SerializerProvider sp) throws IOException, JsonProcessingException {
            jg.writeString(Headers.ISO2822DateFormat.format(t));
        }
    }

    private static class DateTimeDeserializer extends JsonDeserializer<ZonedDateTime> {

        @Override
        public boolean isCachable() {
            return true;
        }

        @Override
        public Class<?> handledType() {
            return ZonedDateTime.class;
        }

        private static final boolean isNumbers(CharSequence s) {
            int len = s.length();
            boolean result = s.length() > 0;
            if (result) {
                result = true;
                for (int i = 0; i < len; i++) {
                    char c = s.charAt(i);
                    if (!Character.isDigit(c)) {
                        result = false;
                        break;
                    }
                }
            }
            return result;
        }

        @Override
        public ZonedDateTime deserialize(JsonParser jp, DeserializationContext dc) throws IOException, JsonProcessingException {
            String string = jp.readValueAs(String.class);
            if (isNumbers(string)) {
                return TimeUtil.fromUnixTimestamp(Long.parseLong(string));
            }
            return TimeUtil.fromHttpHeaderFormat(string);
        }
    }
}
