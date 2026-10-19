-- The W3C traceparent of the trace each event was written in (#96), such as the Network's call that approved an
-- Authorization: the relay sends the event as part of that trace, so the platform's consumer span joins it. Null when
-- no trace was current.
alter table outbox add column trace_parent text;
