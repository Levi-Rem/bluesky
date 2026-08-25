package org.bluesky.training.mapdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpMapDataClientTest {
    private MockRestServiceServer server;
    private HttpMapDataClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new HttpMapDataClient(restTemplate, "http://data-prep");
    }

    @Test
    void acceptsACompleteSnapshotWithValidGeoJson() {
        server.expect(requestTo("http://data-prep/api/map/runtime-layers"))
                .andRespond(withSuccess(validSnapshot(), MediaType.APPLICATION_JSON));

        MapLayersResponse response = client.fetch();

        assertThat(response.isAvailable()).isTrue();
        assertThat(response.getLayers()).hasSize(4);
        server.verify();
    }

    @Test
    void rejectsUnknownCategories() {
        String body = validSnapshot().replace("PHYSICAL_SECTOR", "UNKNOWN_SECTOR");
        server.expect(requestTo("http://data-prep/api/map/runtime-layers"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::fetch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知分类");
    }

    @Test
    void rejectsInvalidGeoJsonCoordinates() {
        String body = validSnapshot().replace("[121.5,31.2]", "[121.5,999]");
        server.expect(requestTo("http://data-prep/api/map/runtime-layers"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::fetch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("几何不合法");
    }

    @Test
    void propagatesNonSuccessResponsesForSilentDegradationByTheService() {
        server.expect(requestTo("http://data-prep/api/map/runtime-layers"))
                .andRespond(withServerError());

        assertThatThrownBy(client::fetch).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsMalformedJson() {
        server.expect(requestTo("http://data-prep/api/map/runtime-layers"))
                .andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::fetch).isInstanceOf(RuntimeException.class);
    }

    private String validSnapshot() {
        return "{\"layers\":["
                + "{\"category\":\"WAYPOINT\",\"name\":\"航路点\",\"count\":1,\"features\":[{"
                + "\"featureId\":\"waypoint:PUD\",\"featureType\":\"WAYPOINT\","
                + "\"pointType\":\"VOR\",\"code\":\"PUD\",\"name\":\"浦东\","
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[121.5,31.2]}}]},"
                + emptyLayer("AIRWAY", "航线") + ","
                + emptyLayer("PHYSICAL_SECTOR", "扇区") + ","
                + emptyLayer("WEATHER", "天气") + "]}";
    }

    private String emptyLayer(String category, String name) {
        return "{\"category\":\"" + category + "\",\"name\":\"" + name
                + "\",\"count\":0,\"features\":[]}";
    }
}
