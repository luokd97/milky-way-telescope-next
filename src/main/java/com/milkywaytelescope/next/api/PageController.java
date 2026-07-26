package com.milkywaytelescope.next.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String dashboard() {
        return "forward:/index.html";
    }

    @GetMapping("/admin")
    public String admin() {
        return "forward:/admin/index.html";
    }
}
