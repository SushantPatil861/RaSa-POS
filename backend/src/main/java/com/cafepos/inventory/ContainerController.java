package com.cafepos.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ContainerController {

    private final ContainerService containers;

    public ContainerController(ContainerService containers) {
        this.containers = containers;
    }

    @GetMapping("/containers")
    @PreAuthorize("hasAnyAuthority('inventory.view','containers.manage')")
    public List<ContainerService.ContainerDto> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Long ingredientId,
                                                    @RequestParam(defaultValue = "100") int limit) {
        return containers.list(status, ingredientId, limit);
    }

    @PostMapping("/containers")
    @PreAuthorize("hasAuthority('containers.manage')")
    public ContainerService.ContainerDto open(@RequestBody ContainerService.OpenRequest r) {
        return containers.open(r);
    }

    @PostMapping("/containers/{id}/finish")
    @PreAuthorize("hasAuthority('containers.manage')")
    public ContainerService.ContainerDto finish(@PathVariable long id, @RequestBody ContainerService.FinishRequest r) {
        return containers.finish(id, r);
    }
}
