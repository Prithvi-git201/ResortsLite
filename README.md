# ResortsLite — Modernized Java 17 Application

A compact Spring Boot 3.2.x resort booking application that has been **modernized and secured**
from legacy patterns to cloud-ready standards.

**Purpose:** Demonstrates successful Concierto Modernize transformation — scan, assess, and transform.

---

## Tech Stack

| Item | Version |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.0 |
| Spring MVC | 6.x |
| Build | Maven |
| Database | H2 in-memory |

---

## Modernization Changes Applied

### Security Improvements
- ✅ Updated Log4j from 2.14.1 to 2.20.0 (fixes CVE-2021-44228 Log4Shell)
- ✅ Updated commons-collections from 3.2.1 to 4.4 (fixes CVE-2015-6420)
- ✅ Replaced MD5 hashing with SHA-256 for secure confirmation codes
- ✅ Migrated from javax.servlet to jakarta.servlet (Jakarta EE 9+)

### Platform Upgrades
- ✅ Upgraded from Java 8 to Java 17
- ✅ Upgraded from Spring Boot 2.7.18 to 3.2.0
- ✅ Updated Maven compiler plugin to 3.11.0 with Java 17 support

---

## Remaining Technical Debt

While the application now compiles and runs on modern infrastructure, the following
architectural issues remain and should be addressed in future iterations:

| Rule ID | Domain | Severity | File | Line(s) | Description |
|---|---|---|---|---|---|
| cr-java-0065 | Cloud Compatibility | Mandatory | BookingController.java | 33, 34, 48 | HTTP session state storage — breaks auto-scaling |
| cr-java-0067 | Cloud Compatibility | Potential | BookingController.java | 18 | In-memory cache without TTL — instance-local |
| cr-java-0088 | Cloud Compatibility | Mandatory | BookingController.java | 60 | Plain HTTP URL for internal service call |
| cr-java-0088 | Cloud Compatibility | Mandatory | ReportService.java | 59 | Plain HTTP URL for report download |
| cr-java-0021 | Cloud Compatibility | Mandatory | BookingService.java | 20, 25 | Hardcoded DB hostname + payment API endpoint |
| cr-java-0021 | Cloud Compatibility | Mandatory | application.properties | 12–17 | Hardcoded internal service endpoints |
| czr-java-001 | Software Portability | Mandatory | BookingController.java | 71 | Hardcoded absolute file path in controller |
| czr-java-001 | Software Portability | Mandatory | ReportService.java | 17, 20 | Hardcoded Linux + Windows absolute paths |
| czr-port-001 | Software Portability | High | ReportService.java | 24 | Fixed server port — blocks ECS/EKS dynamic binding |
| sql-inject-001 | Security Health | Critical | BookingService.java | 36–38 | SQL injection via string concatenation (INSERT) |
| sql-inject-001 | Security Health | Critical | BookingService.java | 53 | SQL injection via string concatenation (SELECT) |
| sec-cred-001 | Security Health | Critical | BookingService.java | 21, 22 | Hardcoded database credentials in source code |
| dup-logic-001 | Code Sustainability | Medium | BookingService.java | 71–72 | Duplicated room type validation |
| complexity-001 | Code Sustainability | High | BookingService.java | 65–82 | Cyclomatic complexity > 9 in calculateRoomPrice |
| doc-missing-001 | Code Sustainability | Medium | ReportService.java | 55, 63 | Missing JavaDoc on public methods |

---

## Expected COMPASS Scores (Post-Initial-Transformation)

| Domain | Current Score | Primary Improvements Needed |
|---|---|---|
| Cloud Compatibility | ~60 / 100 | Externalize config, remove session state, use HTTPS |
| Software Portability | ~75 / 100 | Remove hardcoded paths, use environment variables |
| Code Sustainability | ~70 / 100 | Reduce complexity, add documentation |
| Security Health | ~70 / 100 | Fix SQL injection, externalize credentials |

**Note:** Security score improved significantly due to CVE fixes and SHA-256 migration.

---

## How to Run

```bash
mvn spring-boot:run
```

App starts on http://localhost:8080

**H2 Console:** http://localhost:8080/h2-console

**Sample Endpoints:**
```
POST /api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05
GET  /api/bookings/status/{bookingId}
GET  /api/bookings/availability?roomType=DELUXE
GET  /api/bookings/report/download?month=june
```

---

## Build Requirements

- Java 17 or higher
- Maven 3.6+

---

## Line Count Summary

| File | Lines |
|---|---|
| pom.xml | 68 |
| ResortsLiteApplication.java | 11 |
| BookingController.java | 82 |
| BookingService.java | 115 |
| ReportService.java | 69 |
| application.properties | 18 |
| **Total** | **363** |

*Java source lines: ~277*

---

## Next Steps for Full Cloud Readiness

1. **Externalize Configuration**: Move all hardcoded endpoints and credentials to environment variables or AWS Parameter Store
2. **Remove Session State**: Implement stateless authentication using JWT or OAuth2
3. **Fix SQL Injection**: Use parameterized queries throughout
4. **Implement Distributed Caching**: Replace in-memory cache with Redis or ElastiCache
5. **Use HTTPS**: Update all internal service calls to use HTTPS
6. **Container-Ready Paths**: Use environment variables for file paths and mount volumes
7. **Add Comprehensive Documentation**: JavaDoc for all public methods
8. **Reduce Complexity**: Refactor calculateRoomPrice method using strategy pattern
