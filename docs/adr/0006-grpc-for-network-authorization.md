# gRPC with ISO 8583 field names for Network Authorization, not ISO 8583 itself

Real Networks send Authorization requests as ISO 8583 messages over persistent TCP connections. We use gRPC with field names borrowed from ISO 8583 instead: it keeps the same contract-first, low-latency request/response shape and avoids building a binary message codec before the first Charge can flow. Speaking real ISO 8583, a natural fit for Vert.x, is a later stretch milestone.
