package com.authx.clustertest.matrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;

/**
 * Standalone entry point for the SDK matrix benchmark.
 * Run via: {@code java -cp build/libs/cluster-test-*.jar com.authx.clustertest.matrix.MatrixMain}
 */
public final class MatrixMain {

    public static void main(String[] args) throws Exception {
        long perCellMs = args.length > 0 ? Long.parseLong(args[0]) : 5_000;
        String outDir = System.getenv().getOrDefault("RESULTS_DIR", "./results");

        System.out.println("[Matrix] Running A-class SDK matrix (per-cell duration=" + perCellMs + "ms)");
        long t0 = System.currentTimeMillis();
        var cells = SdkMatrix.runAll(perCellMs);
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("[Matrix] Done — " + cells.size() + " cells in " + elapsed + "ms");

        // Write JSON
        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path outDirPath = Paths.get(outDir);
        Files.createDirectories(outDirPath);
        var jsonPath = outDirPath.resolve("matrix.json");
        var meta = new LinkedHashMap<String, Object>();
        meta.put("perCellDurationMs", perCellMs);
        meta.put("totalCells", cells.size());
        meta.put("totalElapsedMs", elapsed);
        meta.put("generatedAt", java.time.Instant.now().toString());
        meta.put("cells", cells);
        mapper.writeValue(jsonPath.toFile(), meta);
        System.out.println("[Matrix] Wrote " + jsonPath);

        // Generate HTML
        String chartJs = new String(new ClassPathResource("web/chart.min.js").getInputStream().readAllBytes());
        String json = mapper.writeValueAsString(meta);
        String html = MatrixHtml.build(chartJs, json);
        var htmlPath = outDirPath.resolve("matrix-report.html");
        Files.writeString(htmlPath, html);
        System.out.println("[Matrix] Wrote " + htmlPath);
    }

    private MatrixMain() {}
}
