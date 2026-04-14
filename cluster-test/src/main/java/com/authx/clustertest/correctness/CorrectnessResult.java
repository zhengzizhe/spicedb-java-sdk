package com.authx.clustertest.correctness;

public record CorrectnessResult(String name, String status, long durationMs, String details) {
    public static CorrectnessResult pass(String name, long ms) { return new CorrectnessResult(name, "PASS", ms, ""); }
    public static CorrectnessResult fail(String name, long ms, String why) { return new CorrectnessResult(name, "FAIL", ms, why); }
}
