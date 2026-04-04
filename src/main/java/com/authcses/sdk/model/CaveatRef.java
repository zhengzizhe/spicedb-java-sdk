package com.authcses.sdk.model;

import java.util.Map;

/** Caveat reference for conditional permissions. Replaces (String caveatName, Map<String, Object> caveatContext). */
public record CaveatRef(String name, Map<String, Object> context) {}
