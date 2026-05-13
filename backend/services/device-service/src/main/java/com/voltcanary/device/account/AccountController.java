package com.voltcanary.device.account;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    @GetMapping("/current")
    public Map<String, String> currentAccount() {
        return Map.of("message", "current account endpoint is not implemented yet");
    }
}
