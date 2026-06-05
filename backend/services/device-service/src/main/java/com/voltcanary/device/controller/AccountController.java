package com.voltcanary.device.controller;

import com.voltcanary.device.domain.Device;
import com.voltcanary.device.dto.AccountResponse;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.device.service.AccountService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final AuthenticatedUserResolver userResolver;

    public AccountController(AccountService accountService, AuthenticatedUserResolver userResolver) {
        this.accountService = accountService;
        this.userResolver = userResolver;
    }

    @GetMapping("/current")
    public AccountResponse currentAccount(@RequestHeader HttpHeaders headers) {
        return accountService.toResponse(accountService.currentAccount(userResolver.resolve(headers)));
    }
}
