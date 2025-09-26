# Используем образ Maven для сборки
FROM maven:3.8.8-eclipse-temurin-17 AS builder

# Устанавливаем рабочую директорию для сборки
WORKDIR /build

# Копируем файлы проекта в контейнер
COPY . .

# Собираем проект с помощью Maven
RUN mvn clean package

# Используем более легкий образ для запуска
FROM openjdk:17-jdk-slim

# Устанавливаем рабочую директорию
WORKDIR /app

# Копируем собранный JAR из предыдущего этапа
COPY --from=builder /build/target/*.jar app.jar

# Запускаем приложение
CMD ["java", "-jar", "app.jar"]