package com.nxr.platform;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void platformSummaryReturnsRealCounts() throws Exception {
        mockMvc.perform(get("/api/platform/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phase").value("phase-1-real-slice"))
            .andExpect(jsonPath("$.publishedCount").value(greaterThanOrEqualTo(3)))
            .andExpect(jsonPath("$.submissionCount").value(greaterThanOrEqualTo(5)));
    }

    @Test
    void publicOverviewReturnsFeaturedCards() throws Exception {
        mockMvc.perform(get("/api/public/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.platformName").value("NXR Grading"))
            .andExpect(jsonPath("$.featuredCards", hasSize(3)))
            .andExpect(jsonPath("$.publishedCertificates").value(greaterThanOrEqualTo(3)));
    }

    @Test
    void publishedCardLookupIsCaseInsensitive() throws Exception {
        mockMvc.perform(get("/api/public/cards/vra003"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.certId").value("VRA003"))
            .andExpect(jsonPath("$.cardName").value("Umbreon VMAX Alternate Art"));
    }

    @Test
    void adminLoginAcceptsSeededUser() throws Exception {
        mockMvc.perform(
                post("/api/admin/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "username": "nxr_admin",
                          "password": "NxrAdmin2026!"
                        }
                        """)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("nxr_admin"))
            .andExpect(jsonPath("$.roleCode").value("superadmin"));
    }

    @Test
    void createSubmissionAddsRecordToAdminList() throws Exception {
        String certId = "NXRTEST" + System.nanoTime();

        mockMvc.perform(
                post("/api/admin/submissions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "certId": "%s",
                          "cardName": "Mew ex",
                          "yearLabel": "2024",
                          "brandName": "Pokemon",
                          "playerName": "Mew",
                          "varietyName": "Special Art Rare",
                          "setName": "Paldean Fates",
                          "cardNumber": "232/091",
                          "languageCode": "EN",
                          "populationValue": 1,
                          "centeringScore": 9.5,
                          "edgesScore": 9.0,
                          "cornersScore": 9.5,
                          "surfaceScore": 9.5,
                          "entryNotes": "Created in integration test."
                        }
                        """.formatted(certId))
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.certId").value(certId))
            .andExpect(jsonPath("$.statusCode").value("pending"));

        mockMvc.perform(get("/api/admin/submissions").param("query", certId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.items[0].certId").value(certId));
    }

    @Test
    void folderImportStagesMediaByExactCertId() throws Exception {
        mockMvc.perform(
                multipart("/api/admin/media/import-folder")
                    .file(new MockMultipartFile("image_files", "NXR2026042602_A.jpg", "image/jpeg", "front".getBytes()))
                    .file(new MockMultipartFile("image_files", "NXR2026042602_B.jpg", "image/jpeg", "back".getBytes()))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matchedEntries").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.savedFiles").value(2))
            .andExpect(jsonPath("$.updatedSubmissionIds", hasItem(1005)));

        mockMvc.perform(get("/api/admin/submissions/1005"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.media.length()").value(greaterThanOrEqualTo(2)))
            .andExpect(jsonPath("$..mediaStageCode", hasItem("staged")))
            .andExpect(jsonPath("$..publicUrl", hasItem(containsString("/media/staged/"))));
    }

    @Test
    void publishSubmissionPromotesStagedMediaToPublicCard() throws Exception {
        mockMvc.perform(
                multipart("/api/admin/media/import-folder")
                    .file(new MockMultipartFile("image_files", "NXR2026042602_A.jpg", "image/jpeg", "front".getBytes()))
                    .file(new MockMultipartFile("image_files", "NXR2026042602_B.jpg", "image/jpeg", "back".getBytes()))
            )
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/media/submissions/1005/publish"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.certId").value("NXR2026042602"))
            .andExpect(jsonPath("$.statusCode").value("published"))
            .andExpect(jsonPath("$.publishedFrontUrl", containsString("/media/published/")))
            .andExpect(jsonPath("$.publishedBackUrl", containsString("/media/published/")));

        mockMvc.perform(get("/api/public/cards/NXR2026042602"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.certId").value("NXR2026042602"))
            .andExpect(jsonPath("$.frontImageUrl", containsString("/media/published/")))
            .andExpect(jsonPath("$.backImageUrl", containsString("/media/published/")));
    }
}
