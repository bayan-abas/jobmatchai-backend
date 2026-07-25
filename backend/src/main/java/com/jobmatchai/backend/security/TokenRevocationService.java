package com.jobmatchai.backend.security;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// בזיכרון בלבד ולא ב-DB - לא שורד ריסטארט ולא משותף בין instances, אבל בפרויקט הזה (instance בודד)
// זה פשוט אומר שריסטארט "שוכח" רוויקציות ישנות, לא פחות טוב ממה שהיה בלי הפיצ'ר הזה בכלל
@Service
public class TokenRevocationService {

    private final Map<String, Instant> revokedBeforeByEmail = new ConcurrentHashMap<>();

    // מסמן שכל הטוקנים שהונפקו למשתמש הזה עד הרגע הנתון בטלים (למשל אחרי שינוי סיסמה או התנתקות כפויה)
    public void revokeTokensIssuedBefore(String email, Instant instant) {
        revokedBeforeByEmail.merge(email, instant,
                (existing, incoming) -> incoming.isAfter(existing) ? incoming : existing);
    }

    // בודק אם הטוקן הספציפי הזה בוטל - כלומר הונפק לפני נקודת הביטול האחרונה שנשמרה למשתמש
    public boolean isRevoked(String email, Date issuedAt) {
        Instant revokedBefore = revokedBeforeByEmail.get(email);
        return revokedBefore != null && issuedAt != null && issuedAt.toInstant().isBefore(revokedBefore);
    }
}
