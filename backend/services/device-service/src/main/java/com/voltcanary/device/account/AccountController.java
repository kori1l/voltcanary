package com.voltcanary.device.account;

import com.voltcanary.device.security.JwtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final JwtService jwtService;

    public AccountController(AccountService accountService, JwtService jwtService) {
        this.accountService = accountService;
        this.jwtService = jwtService;
    }

    @GetMapping("/current")
    public AccountResponse currentAccount(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return accountService.toResponse(accountService.currentAccount(jwtService.parseBearerToken(authorization)));
    }
}
