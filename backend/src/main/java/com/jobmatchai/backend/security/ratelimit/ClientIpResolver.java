package com.jobmatchai.backend.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    @Value("${app.proxy.trust-x-forwarded-for:false}")
    private boolean trustXForwardedFor;

    // מוצא את כתובת ה-IP האמיתית של הלקוח, כדי שרייט-לימיטינג יעבוד נכון גם כשיש פרוקסי לפני השרת
    public String resolve(HttpServletRequest request) {
        // אפשר לזייף את ה-header הזה בקלות - סומכים עליו רק אם מוגדר במפורש שיש פרוקסי אמין מלפנים
        if (trustXForwardedFor) {
            String header = request.getHeader("X-Forwarded-For");
            if (header != null && !header.isBlank()) {

                String candidate = header.split(",")[0].trim();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
