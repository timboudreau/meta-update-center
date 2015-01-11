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
import com.timboudreau.metaupdatecenter.borrowed.SpecificationVersion;
import java.io.IOException;
import java.util.regex.Pattern;
import org.joda.time.DateTime;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = JacksonConfigurer.class)
public class JsonConfig implements JacksonConfigurer {

    @Override
    public ObjectMapper configure(ObjectMapper mapper) {
        SimpleModule sm = new SimpleModule("specversion", new Version(1, 0, 0, null, "org.netbeans.modules", "SpecificationVersion"));
        // For logging purposes, iso dates are more useful
        sm.addSerializer(new SpecVersionSerializer());
        sm.addSerializer(new DateTimeSerializer());
        sm.addDeserializer(DateTime.class, new DateTimeDeserializer());
        mapper.registerModule(sm);
//        mapper.registerModule(new JodaModule());
//        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
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

    private static class DateTimeSerializer extends JsonSerializer<DateTime> {

        @Override
        public Class<DateTime> handledType() {
            return DateTime.class;
        }

        @Override
        public void serialize(DateTime t, JsonGenerator jg, SerializerProvider sp) throws IOException, JsonProcessingException {
            jg.writeString(Headers.ISO2822DateFormat.print(t));
        }
    }

    private static class DateTimeDeserializer extends JsonDeserializer<DateTime> {

        @Override
        public boolean isCachable() {
            return true;
        }

        @Override
        public Class<?> handledType() {
            return DateTime.class;
        }

        private static final Pattern NUMBERS = Pattern.compile("^\\d+$");

        @Override
        public DateTime deserialize(JsonParser jp, DeserializationContext dc) throws IOException, JsonProcessingException {
            String string = jp.readValueAs(String.class);
            if (NUMBERS.matcher(string).matches()) {
                return new DateTime(Long.parseLong(string));
            }
            return Headers.ISO2822DateFormat.parseDateTime(string);
        }
    }
}
