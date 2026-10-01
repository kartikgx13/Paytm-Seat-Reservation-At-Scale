package com.paytm.seats.show;

import com.paytm.seats.show.ShowDtos.CreateShowRequest;
import com.paytm.seats.show.ShowDtos.ShowView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService shows;

    public ShowController(ShowService shows) {
        this.shows = shows;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowView create(@Valid @RequestBody CreateShowRequest req) {
        return shows.create(req);
    }

    @GetMapping("/{id}")
    public ShowView get(@PathVariable UUID id) {
        return shows.get(id);
    }
}
