/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.elasticsearch.core;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.util.Locale;

import org.junit.Before;
import org.junit.Test;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.annotation.Transient;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext;
import org.springframework.data.elasticsearch.entities.Car;
import org.springframework.data.elasticsearch.entities.GeoEntity;
import org.springframework.data.geo.Point;

/**
 * @author Artur Konczak
 * @author Mohsin Husen
 * @author Oliver Gierke
 */
public class DefaultEntityMapperTests {

	public static final String JSON_STRING = "{\"name\":\"Grat\",\"model\":\"Ford\"}";
	public static final String CAR_MODEL = "Ford";
	public static final String CAR_NAME = "Grat";
	DefaultEntityMapper entityMapper;

	@Before
	public void init() {
		entityMapper = new DefaultEntityMapper(new SimpleElasticsearchMappingContext());
	}

	@Test
	public void shouldMapObjectToJsonString() throws IOException {
		//Given

		//When
		String jsonResult = entityMapper.mapToString(Car.builder().model(CAR_MODEL).name(CAR_NAME).build());

		//Then
		assertThat(jsonResult).isEqualTo(JSON_STRING);
	}

	@Test
	public void shouldMapJsonStringToObject() throws IOException {
		//Given

		//When
		Car result = entityMapper.mapToObject(JSON_STRING, Car.class);

		//Then
		assertThat(result.getName()).isEqualTo(CAR_NAME);
		assertThat(result.getModel()).isEqualTo(CAR_MODEL);
	}

	@Test
	public void shouldMapGeoPointElasticsearchNames() throws IOException {
		//given
		final Point point = new Point(10, 20);
		final String pointAsString = point.getX() + "," + point.getY();
		final double[] pointAsArray = {point.getX(), point.getY()};
		final GeoEntity geoEntity = GeoEntity.builder()
				.pointA(point).pointB(GeoPoint.fromPoint(point)).pointC(pointAsString).pointD(pointAsArray)
				.build();
		//when
		String jsonResult = entityMapper.mapToString(geoEntity);

		//then
		assertThat(jsonResult).contains(pointTemplate("pointA", point));
		assertThat(jsonResult).contains(pointTemplate("pointB", point));
		assertThat(jsonResult).contains(String.format(Locale.ENGLISH, "\"%s\":\"%s\"", "pointC", pointAsString));
		assertThat(jsonResult).contains(String.format(Locale.ENGLISH, "\"%s\":[%.1f,%.1f]", "pointD", pointAsArray[0], pointAsArray[1]));
	}
	
	@Test // DATAES-464
	public void ignoresReadOnlyProperties() throws IOException {
		
		// given
		Sample sample = new Sample();
		sample.readOnly = "readOnly";
		sample.property = "property";
		sample.transientProperty = "transient";
		sample.annotatedTransientProperty = "transient";
		
		// when
		String result = entityMapper.mapToString(sample);
		
		// then
		assertThat(result).contains("\"property\"");
		
		assertThat(result).doesNotContain("readOnly");
		assertThat(result).doesNotContain("transientProperty");
		assertThat(result).doesNotContain("annotatedTransientProperty");
	}

	private String pointTemplate(String name, Point point) {
		return String.format(Locale.ENGLISH, "\"%s\":{\"lat\":%.1f,\"lon\":%.1f}", name, point.getX(), point.getY());
	}
	
	public static class Sample {
		
		
		public @ReadOnlyProperty String readOnly;
		public @Transient String annotatedTransientProperty;
		public transient String transientProperty;
		public String property;
	}
}
