plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "3.2.4"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"
}

group = "com.realteeth"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// server
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-webflux") // for WebClient only

	// db
	runtimeOnly("com.mysql:mysql-connector-j")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")

	// for kotlin
	implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

	// test
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("io.mockk:mockk:1.13.13")
	testImplementation("com.ninja-squad:springmockk:4.0.2")
	testRuntimeOnly("com.h2database:h2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
