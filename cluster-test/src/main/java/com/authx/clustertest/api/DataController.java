package com.authx.clustertest.api;

import com.authx.clustertest.config.ClusterProps;
import com.authx.clustertest.data.BulkImporter;
import com.authx.clustertest.data.DataModel;
import com.authx.clustertest.data.RelationshipFileGenerator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Paths;
import java.util.Map;

@RestController
@RequestMapping("/test/data")
public class DataController {
    private final RelationshipFileGenerator gen;
    private final BulkImporter importer;
    private final ClusterProps props;
    private volatile long generated, imported;

    public DataController(RelationshipFileGenerator g, BulkImporter i, ClusterProps p) {
        this.gen = g; this.importer = i; this.props = p;
    }

    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestParam(defaultValue = "false") boolean small) throws Exception {
        if (!props.isLeader()) return Map.of("status", "skipped", "reason", "non-leader");
        var path = Paths.get(props.resultsDir(), "relations.txt");
        var model = small ? DataModel.small() : DataModel.defaults();
        long t0 = System.currentTimeMillis();
        generated = gen.generate(path, model);
        return Map.of("status", "ok", "count", generated,
                "file", path.toString(),
                "elapsedMs", System.currentTimeMillis() - t0);
    }

    @PostMapping("/import")
    public Map<String, Object> importData() throws Exception {
        if (!props.isLeader()) return Map.of("status", "skipped", "reason", "non-leader");
        var path = Paths.get(props.resultsDir(), "relations.txt");
        long t0 = System.currentTimeMillis();
        imported = importer.importFile(path);
        return Map.of("status", "ok", "count", imported,
                "elapsedMs", System.currentTimeMillis() - t0);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("generated", generated, "imported", imported);
    }
}
