package app.monthlyspend.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/", "/app"})
    public String index() {
        return "forward:/index.html";
    }
}

