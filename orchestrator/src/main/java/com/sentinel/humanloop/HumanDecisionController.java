package com.sentinel.humanloop;

import com.sentinel.humanloop.HumanDecisionRequests.EditRequest;
import com.sentinel.humanloop.HumanDecisionRequests.RejectRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@CrossOrigin(origins = "${sentinel.ui.cors-origin:http://localhost:5173}")
public class HumanDecisionController {

    private final HumanDecisionService service;

    public HumanDecisionController(HumanDecisionService service) {
        this.service = service;
    }

    @PostMapping("/incidents/{id}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@PathVariable UUID id) {
        service.accept(id);
    }

    @PostMapping("/incidents/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID id, @RequestBody RejectRequest req) {
        service.reject(id, req.reason());
    }

    @PatchMapping("/incidents/{id}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void edit(@PathVariable UUID id, @RequestBody EditRequest req) {
        service.edit(id, req);
    }
}
