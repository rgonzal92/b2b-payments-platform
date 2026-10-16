package dev.rgonz.payments.platform.payables;

/** An Approver as their token names them: a stable user ID, and a user name for people to read. */
record Approver(String id, String name) {}
