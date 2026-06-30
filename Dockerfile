# Build stage
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew build -x test

# Run stage
FROM eclipse-temurin:17-jre
WORKDIR /app
# UTF-8 로케일을 명시한다. 미설정 시 컨테이너 LANG 이 C/POSIX 가 되어
# JVM 의 sun.jnu.encoding 이 ASCII 로 잡히고, 한글이 포함된 업로드 파일명을
# 디스크에서 찾지 못해(404) 옵션 사진이 깨진다.
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8", "-jar", "app.jar"]
