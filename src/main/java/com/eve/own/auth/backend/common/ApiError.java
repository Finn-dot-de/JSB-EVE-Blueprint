package com.eve.own.auth.backend.common;

/**
 * Die einheitliche Fehlerantwort der API.
 *
 * <p>Das Frontend liest {@code message} - dieselbe Form, die die Controller
 * zuvor als handgebaute {@code Map.of("message", ...)} zurueckgegeben haben.</p>
 */
public record ApiError(String message) {}
