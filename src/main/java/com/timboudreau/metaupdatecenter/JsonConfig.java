package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.mastfrog.jackson.JacksonConfigurer;
import java.io.IOException;
import org.openide.modules.SpecificationVersion;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = JacksonConfigurer.class)
public class JsonConfig implements JacksonConfigurer {

    @Override
    public ObjectMapper configure(ObjectMapper mapper) {
        SimpleModule sm = new SimpleModule("specversion", new Version(1, 0, 0, null, "org.netbeans.modules", "SpecificationVersion"));
        sm.addSerializer(new SpecVersionSerializer());
        mapper.registerModule(sm);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
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
}
