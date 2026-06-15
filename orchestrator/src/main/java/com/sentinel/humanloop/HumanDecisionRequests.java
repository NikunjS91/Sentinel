package com.sentinel.humanloop;

public class HumanDecisionRequests {
    public record AcceptRequest() {}
    public record RejectRequest(String reason) {}
    public record EditRequest(String summary, String rootCause, String recommendedAction) {}
}
