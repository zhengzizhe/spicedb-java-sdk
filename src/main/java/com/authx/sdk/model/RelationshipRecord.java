package com.authx.sdk.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A relationship including the optional stored caveat and expiration metadata. */
public record RelationshipRecord(Tuple tuple, @Nullable CaveatRef caveat, @Nullable Instant expiresAt) {}
