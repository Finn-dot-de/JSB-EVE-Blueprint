package com.eve.own.auth.backend.domain.buybot.dto;

public interface TypeDetailsProjection {
    Long getTypeId();
    String getTypeName();
    Double getVolume();
    Long getCategoryId();
}