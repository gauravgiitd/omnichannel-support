package com.omnichannel.support.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PrototypePageController {

    @GetMapping("/")
    public String landing() {
        return "forward:/index.html";
    }

    @GetMapping("/login")
    public String login() {
        return "forward:/login.html";
    }

    @GetMapping("/customer")
    public String customer() {
        return "forward:/customer.html";
    }

    @GetMapping("/agent")
    public String agent() {
        return "forward:/agent.html";
    }

    @GetMapping("/admin")
    public String admin() {
        return "forward:/admin.html";
    }

    @GetMapping("/jtbd")
    public String jtbd() {
        return "forward:/jtbd.html";
    }
}
