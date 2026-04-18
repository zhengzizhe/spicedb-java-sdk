package com.authx.clustertest;

import com.authx.clustertest.config.ClusterProps;
import com.authx.clustertest.config.ResultsRepo;
import com.authx.clustertest.report.EnvironmentInfo;
import com.authx.clustertest.report.HtmlReportGenerator;

/**
 * Offline report regeneration: reads existing JSON results under
 * {@code RESULTS_DIR} and rewrites {@code report.html} without needing the
 * cluster to be running. Run via:
 * {@code java -cp build/libs/cluster-test-*.jar com.authx.clustertest.ReportMain}
 */
public final class ReportMain {
    public static void main(String[] args) throws Exception {
        String resultsDir = System.getenv().getOrDefault("RESULTS_DIR", "./results");
        var props = new ClusterProps(
                0, 3, 8091, resultsDir,
                new ClusterProps.SpiceDb("localhost:50051", "testkey"),
                new ClusterProps.Toxiproxy("localhost", 8474, false));
        var repo = new ResultsRepo(props);
        var gen = new HtmlReportGenerator(repo, new EnvironmentInfo());
        var path = gen.generate();
        System.out.println("Report regenerated: " + path);
    }
}
