/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.alarmpanel.internal.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.alarmpanel.internal.AlarmPanelBindingConstants;
import org.openhab.binding.alarmpanel.internal.handler.AlarmPanelBridgeHandler;
import org.openhab.binding.alarmpanel.internal.pin.PinRecord;
import org.openhab.binding.alarmpanel.internal.pin.PinStore;
import org.openhab.core.io.rest.RESTConstants;
import org.openhab.core.io.rest.RESTResource;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.binding.ThingHandler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JSONRequired;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsApplicationSelect;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsName;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * REST resource for managing PINs and reading bridge state.
 *
 * <p>
 * Endpoints (all under {@code /rest/alarmpanel}):
 *
 * <pre>
 *   GET    /rest/alarmpanel/state        -> bridge state + PIN count + lockout
 *   GET    /rest/alarmpanel/pin          -> list PINs (labels + ids, never the hash)
 *   POST   /rest/alarmpanel/pin          -> add a PIN; body {"label": "Alice", "pin": "12345"}
 *   DELETE /rest/alarmpanel/pin/{id}     -> remove a PIN by id
 * </pre>
 *
 * Authentication: relies on the standard openHAB REST auth (Bearer token or
 * basic auth required by core). Plain HTTP from inside the LAN is unauthenticated
 * if the user has the auth flag off.
 *
 * @author openHAB - Initial contribution
 */
@Component(service = AlarmPanelRestResource.class, immediate = true)
@JaxrsResource
@JaxrsName(AlarmPanelRestResource.PATH)
@JaxrsApplicationSelect("(" + JaxrsWhiteboardConstants.JAX_RS_NAME + "=" + RESTConstants.JAX_RS_NAME + ")")
@JSONRequired
@Path("/" + AlarmPanelRestResource.PATH)
@Produces(MediaType.APPLICATION_JSON)
@NonNullByDefault
public class AlarmPanelRestResource implements RESTResource {

    public static final String PATH = "alarmpanel";

    // Reject labels with 4+ consecutive digits — they can leak the PIN if a
    // user copies it into the label (e.g., "Alice-legacy-33733").
    private static final Pattern LABEL_DIGIT_LEAK = Pattern.compile("\\d{4,}");

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmPanelRestResource.class);

    private final ThingRegistry thingRegistry;
    private final Gson gson = new Gson();

    @Activate
    public AlarmPanelRestResource(final @Reference ThingRegistry thingRegistry) {
        this.thingRegistry = thingRegistry;
    }

    @GET
    @Path("/state")
    public Response getState() {
        AlarmPanelBridgeHandler bridge = findBridge();
        if (bridge == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(errorJson("no alarmpanel bridge")).build();
        }
        PinStore ps = bridge.getPinStore();
        Map<String, Object> out = new HashMap<>();
        out.put("state", bridge.getCurrentState().name());
        out.put("pins", ps != null ? ps.size() : 0);
        return Response.ok(gson.toJson(out)).build();
    }

    @GET
    @Path("/pin")
    public Response listPins() {
        AlarmPanelBridgeHandler bridge = findBridge();
        if (bridge == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(errorJson("no alarmpanel bridge")).build();
        }
        PinStore ps = bridge.getPinStore();
        if (ps == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(errorJson("pin store not initialized")).build();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PinRecord r : ps.snapshot()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", r.id);
            row.put("label", r.label);
            row.put("created", r.created.toString());
            if (r.lastUsed != null) {
                row.put("lastUsed", r.lastUsed.toString());
            }
            row.put("disabled", r.disabled);
            rows.add(row);
        }
        return Response.ok(gson.toJson(rows)).build();
    }

    @POST
    @Path("/pin")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addPin(@Nullable JsonObject body) {
        AlarmPanelBridgeHandler bridge = findBridge();
        if (bridge == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(errorJson("no alarmpanel bridge")).build();
        }
        PinStore ps = bridge.getPinStore();
        if (ps == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(errorJson("pin store not initialized")).build();
        }
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorJson("JSON body required: {label, pin}")).build();
        }
        String label;
        char[] pinChars;
        try {
            label = body.has("label") ? body.get("label").getAsString() : "";
            String pin = body.has("pin") ? body.get("pin").getAsString() : "";
            if (label.isBlank() || pin.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("label and pin required, non-empty")).build();
            }
            if (LABEL_DIGIT_LEAK.matcher(label).find()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(errorJson("label may not contain 4+ consecutive digits (PIN leak risk)")).build();
            }
            pinChars = pin.toCharArray();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorJson("invalid JSON: " + e.getMessage())).build();
        }
        try {
            String id = ps.add(label, pinChars);
            Map<String, Object> out = new HashMap<>();
            out.put("id", id);
            out.put("label", label);
            return Response.status(Response.Status.CREATED).entity(gson.toJson(out)).build();
        } catch (RuntimeException e) {
            LOGGER.warn("addPin failed: {}", e.getMessage());
            return Response.serverError().entity(errorJson("add failed: " + e.getMessage())).build();
        }
    }

    @PATCH
    @Path("/pin/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response renamePin(@PathParam("id") String id, @Nullable JsonObject body) {
        AlarmPanelBridgeHandler bridge = findBridge();
        if (bridge == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(errorJson("no alarmpanel bridge")).build();
        }
        PinStore ps = bridge.getPinStore();
        if (ps == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(errorJson("pin store not initialized")).build();
        }
        if (body == null || !body.has("label")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorJson("JSON body required: {label}")).build();
        }
        String newLabel;
        try {
            newLabel = body.get("label").getAsString();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorJson("label must be a string")).build();
        }
        if (newLabel.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorJson("label may not be blank")).build();
        }
        if (LABEL_DIGIT_LEAK.matcher(newLabel).find()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorJson("label may not contain 4+ consecutive digits (PIN leak risk)")).build();
        }
        String matchedId = null;
        for (PinRecord r : ps.snapshot()) {
            if (r.id.equals(id) || r.label.equals(id)) {
                matchedId = r.id;
                break;
            }
        }
        if (matchedId == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(errorJson("no pin with id/label " + id))
                    .build();
        }
        boolean ok = ps.rename(matchedId, newLabel);
        if (!ok) {
            return Response.serverError().entity(errorJson("rename failed")).build();
        }
        Map<String, Object> out = new HashMap<>();
        out.put("id", matchedId);
        out.put("label", newLabel);
        return Response.ok(gson.toJson(out)).build();
    }

    @DELETE
    @Path("/pin/{id}")
    public Response removePin(@PathParam("id") String id) {
        AlarmPanelBridgeHandler bridge = findBridge();
        if (bridge == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(errorJson("no alarmpanel bridge")).build();
        }
        PinStore ps = bridge.getPinStore();
        if (ps == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(errorJson("pin store not initialized")).build();
        }
        if (id == null || id.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(errorJson("id required")).build();
        }
        // Also accept a label as the identifier (convenience for CLI users)
        String matchedId = null;
        for (PinRecord r : ps.snapshot()) {
            if (r.id.equals(id) || r.label.equals(id)) {
                matchedId = r.id;
                break;
            }
        }
        if (matchedId == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(errorJson("no pin with id/label " + id))
                    .build();
        }
        boolean ok = ps.remove(matchedId);
        if (ok) {
            return Response.noContent().build();
        }
        return Response.serverError().entity(errorJson("remove failed")).build();
    }

    private String errorJson(String msg) {
        Map<String, Object> e = new HashMap<>();
        e.put("error", msg);
        return gson.toJson(e);
    }

    private @Nullable AlarmPanelBridgeHandler findBridge() {
        for (Thing t : thingRegistry.getAll()) {
            if (AlarmPanelBindingConstants.THING_TYPE_PANEL.equals(t.getThingTypeUID())) {
                ThingHandler h = t.getHandler();
                if (h instanceof AlarmPanelBridgeHandler) {
                    return (AlarmPanelBridgeHandler) h;
                }
            }
        }
        return null;
    }
}
