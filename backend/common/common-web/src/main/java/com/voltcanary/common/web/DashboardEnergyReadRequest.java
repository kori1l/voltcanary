package com.voltcanary.common.web;

/** Energy is evaluated using freshly renewed registry metadata, never caller-supplied meter rules. */
public record DashboardEnergyReadRequest(DashboardOwnerReadRequest scope, boolean independentMetersConfirmed) {
}
