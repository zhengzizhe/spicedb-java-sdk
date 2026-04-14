package com.authx.clustertest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class ResultsRepo {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path baseDir;

    public ResultsRepo(ClusterProps props) {
        this.baseDir = Paths.get(props.resultsDir(), "instance-" + props.nodeIndex());
        try { Files.createDirectories(baseDir); } catch (IOException e) { throw new RuntimeException(e); }
    }

    public void write(String name, Object data) {
        try {
            mapper.writeValue(baseDir.resolve(name + ".json").toFile(), data);
        } catch (IOException e) {
            throw new RuntimeException("write " + name + " failed", e);
        }
    }

    public <T> T read(String name, Class<T> type) {
        try {
            var f = baseDir.resolve(name + ".json").toFile();
            return f.exists() ? mapper.readValue(f, type) : null;
        } catch (IOException e) {
            throw new RuntimeException("read " + name + " failed", e);
        }
    }

    public Path baseDir() { return baseDir; }
}
