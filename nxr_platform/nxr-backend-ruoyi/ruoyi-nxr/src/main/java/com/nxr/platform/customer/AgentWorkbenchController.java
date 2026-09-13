package com.nxr.platform.customer;

import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import static com.nxr.platform.customer.AgentWorkbenchService.*;

/** Private customer-session authentication is followed by a merchant entitlement check in every service entry. */
@Anonymous
@RestController
@RequestMapping("/api/customer/agent")
public class AgentWorkbenchController {
    private static final String TOKEN="X-NXR-Customer-Token";
    private final CustomerAuthService auth;
    private final AgentWorkbenchService service;
    public AgentWorkbenchController(CustomerAuthService auth,AgentWorkbenchService service) { this.auth=auth; this.service=service; }
    private long owner(String token) { return auth.requireCustomer(token).id(); }

    @org.springframework.web.bind.annotation.ModelAttribute
    public void privateResponses(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.addHeader("Vary", TOKEN);
    }

    @GetMapping("/clients")
    public Page<Client> clients(@RequestHeader(name=TOKEN,required=false) String token,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
        @RequestParam(required=false) String query,@RequestParam(required=false) Boolean active) {
        return service.clients(owner(token),page,pageSize,query,active);
    }
    @PostMapping("/clients")
    public Client createClient(@RequestHeader(name=TOKEN,required=false) String token,@RequestBody ClientRequest request) {
        return service.createClient(owner(token),request);
    }
    @PutMapping("/clients/{id}")
    public Client updateClient(@RequestHeader(name=TOKEN,required=false) String token,@PathVariable long id,@RequestBody ClientRequest request) {
        return service.updateClient(owner(token),id,request);
    }
    @GetMapping("/clients/{id}")
    public ClientDetail client(@RequestHeader(name=TOKEN,required=false) String token,@PathVariable long id) {
        return service.clientDetail(owner(token),id);
    }
    @GetMapping("/intakes")
    public Page<Intake> intakes(@RequestHeader(name=TOKEN,required=false) String token,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
        @RequestParam(required=false) Long clientId,@RequestParam(required=false) String statusCode,@RequestParam(required=false) String query) {
        return service.intakes(owner(token),page,pageSize,clientId,statusCode,query);
    }
    @PostMapping("/intakes")
    public IntakeDetail createIntake(@RequestHeader(name=TOKEN,required=false) String token,@RequestBody IntakeRequest request) {
        return service.createIntake(owner(token),request);
    }
    @GetMapping("/intakes/{id}")
    public IntakeDetail intake(@RequestHeader(name=TOKEN,required=false) String token,@PathVariable long id) {
        return service.intakeDetail(owner(token),id);
    }
    @PostMapping("/intakes/{id}/receive")
    public IntakeDetail receive(@RequestHeader(name=TOKEN,required=false) String token,@PathVariable long id,
        @RequestBody(required=false) NoteRequest request) { return service.receive(owner(token),id,request); }
    @PostMapping("/intakes/{id}/check-in")
    public IntakeDetail checkIn(@RequestHeader(name=TOKEN,required=false) String token,@PathVariable long id,@RequestBody CheckInRequest request) {
        return service.checkIn(owner(token),id,request);
    }
    @GetMapping("/cards")
    public Page<Card> cards(@RequestHeader(name=TOKEN,required=false) String token,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
        @RequestParam(required=false) Long clientId,@RequestParam(required=false) Long intakeId,
        @RequestParam(required=false) String statusCode,@RequestParam(required=false) String query) {
        return service.cards(owner(token),page,pageSize,clientId,intakeId,statusCode,query);
    }
    @PostMapping(value="/cards/{id}/photos",consumes="multipart/form-data")
    public Card uploadPhoto(@RequestHeader(name=TOKEN,required=false) String token,@PathVariable long id,
        @RequestParam String side,@RequestPart("file") MultipartFile file) { return service.uploadPhoto(owner(token),id,side,file); }
    @PostMapping("/submissions")
    public Submission submit(@RequestHeader(name=TOKEN,required=false) String token,@RequestBody SubmissionRequest request) {
        return service.submit(owner(token),request);
    }
    @PostMapping("/cards/{id}/return-check")
    public Card returnCheck(@RequestHeader(name=TOKEN,required=false) String token,@PathVariable long id,@RequestBody ReturnCheckRequest request) {
        return service.returnCheck(owner(token),id,request);
    }
    @GetMapping("/return-shipments")
    public Page<Shipment> shipments(@RequestHeader(name=TOKEN,required=false) String token,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
        @RequestParam(required=false) Long clientId,@RequestParam(required=false) String statusCode,@RequestParam(required=false) String query) {
        return service.shipments(owner(token),page,pageSize,clientId,statusCode,query);
    }
    @PostMapping("/return-shipments")
    public ShipmentDetail createShipment(@RequestHeader(name=TOKEN,required=false) String token,@RequestBody ShipmentRequest request) {
        return service.createShipment(owner(token),request);
    }
    @GetMapping("/return-shipments/{id}")
    public ShipmentDetail shipment(@RequestHeader(name=TOKEN,required=false) String token,@PathVariable long id) {
        return service.shipmentDetail(owner(token),id);
    }
    @PostMapping("/return-shipments/{id}/delivered")
    public ShipmentDetail delivered(@RequestHeader(name=TOKEN,required=false) String token,@PathVariable long id,
        @RequestBody(required=false) NoteRequest request) { return service.delivered(owner(token),id,request); }
    @GetMapping("/events")
    public Page<Event> events(@RequestHeader(name=TOKEN,required=false) String token,
        @RequestParam(required=false) Long clientId,@RequestParam(required=false) Long intakeId,@RequestParam(required=false) Long shipmentId,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return service.events(owner(token),clientId,intakeId,shipmentId,page,pageSize);
    }
}
