# Cloudstream Web — single-container image: Ktor backend also serving the static frontend.
# Multi-stage: FE build (node) → BE fat-jar build (gradle) → JRE runtime.

# --- Stage 1: build frontend ---
FROM node:20-alpine AS frontend
WORKDIR /fe
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# --- Stage 2: build backend (fat jar via shadowJar) ---
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /be
COPY backend/ ./
RUN chmod +x gradlew && ./gradlew --no-daemon shadowJar

# --- Stage 3: runtime ---
FROM eclipse-temurin:21-jre
WORKDIR /app
# Backend fat jar (versioned name → glob)
COPY --from=backend /be/build/libs/*-all.jar app.jar
# Static frontend build, served by Ktor (same-origin: cookies + proxy just work)
COPY --from=frontend /fe/dist ./static
ENV FRONTEND_DIR=/app/static
ENV CLOUDSTREAM_WEB_DATA=/data
ENV PORT=8080
VOLUME ["/data"]
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
