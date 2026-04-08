package com.authx.cluster.benchmark;

public record BenchmarkResult(
    String scenario,
    long totalOps,
    double tps,
    long p50Us,
    long p99Us,
    long p999Us,
    long errors,
    double errorRate,
    long durationMs
) {}
