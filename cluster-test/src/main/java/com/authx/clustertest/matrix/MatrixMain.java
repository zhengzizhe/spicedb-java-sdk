package com.authx.clustertest.matrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Matrix entry point. Usage:
 *   java ... MatrixMain [perCellMs] [mode]
 *
 * mode: "sdk" (default, uses InMemory+LatencySim) or "real" (connects to
 *       SpiceDB at SPICEDB_TARGETS env var, default localhost:50061,62,63)
 */
public final class MatrixMain {

    public static void main(String[] args) throws Exception {
        long perCellMs = args.length > 0 ? Long.parseLong(args[0]) : 5_000;
        String mode = args.length > 1 ? args[1] : "sdk";
        String outDir = System.getenv().getOrDefault("RESULTS_DIR", "./results");

        List<MatrixCell> cells;
        String reportTitle;
        long t0 = System.currentTimeMillis();

        if ("real".equals(mode)) {
            String targetsEnv = System.getenv().getOrDefault("SPICEDB_TARGETS",
                    "localhost:50061,localhost:50062,localhost:50063");
            String key = System.getenv().getOrDefault("SPICEDB_PSK", "testkey");
            String[] targets = targetsEnv.split(",");
            System.out.println("[Matrix] REAL-mode against " + targetsEnv + " (per-cell " + perCellMs + "ms)");
            cells = RealMatrix.runAll(targets, key, perCellMs);
            reportTitle = "real";
        } else {
            System.out.println("[Matrix] SDK-mode (InMemory + 2ms LatencySim, per-cell " + perCellMs + "ms)");
            cells = SdkMatrix.runAll(perCellMs);
            reportTitle = "sdk";
        }

        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("[Matrix] Done — " + cells.size() + " cells in " + elapsed + "ms");

        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path outDirPath = Paths.get(outDir);
        Files.createDirectories(outDirPath);
        var jsonPath = outDirPath.resolve("matrix-" + reportTitle + ".json");
        var meta = new LinkedHashMap<String, Object>();
        meta.put("mode", mode);
        meta.put("perCellDurationMs", perCellMs);
        meta.put("totalCells", cells.size());
        meta.put("totalElapsedMs", elapsed);
        meta.put("generatedAt", java.time.Instant.now().toString());
        meta.put("cells", cells);
        mapper.writeValue(jsonPath.toFile(), meta);
        System.out.println("[Matrix] Wrote " + jsonPath);

        String chartJs = new String(new ClassPathResource("web/chart.min.js").getInputStream().readAllBytes());
        String json = mapper.writeValueAsString(meta);
        String html = MatrixHtml.build(chartJs, json);
        var htmlPath = outDirPath.resolve("matrix-" + reportTitle + "-report.html");
        Files.writeString(htmlPath, html);
        System.out.println("[Matrix] Wrote " + htmlPath);
    }

    private MatrixMain() {}
}
