package org.nem.nis.validation;

import java.math.BigInteger;
import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import org.hibernate.validator.internal.util.Version;
import org.junit.Test;
import org.nem.core.crypto.PrivateKey;
import org.nem.specific.deploy.NisWebAppInitializer;
import org.springframework.beans.factory.InitializingBean;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HibernateValidatorCompatibilityTest {
	@Test
	public void defaultBeanValidationFactoryUsesHibernateValidatorAndStandardMessages() {
		assertEquals("6.2.5.Final", Version.getVersionString());
		assertEquals("javax.validation", Validator.class.getPackage().getName());

		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			final Validator validator = factory.getValidator();

			assertTrue(validator.validate(new ValidationFixture("valid")).isEmpty());

			final Set<ConstraintViolation<ValidationFixture>> violations = validator.validate(new ValidationFixture(""));
			assertEquals(2, violations.size());
			assertTrue(violations.stream().allMatch(violation -> "value".equals(violation.getPropertyPath().toString())));
			assertTrue(violations.stream().anyMatch(violation -> "must not be blank".equals(violation.getMessage())));
		}
	}

	@Test
	public void defaultInterpolatorEvaluatesElAndResourceBundleTemplates() throws Exception {
		final Class<?> expressionFactory = Class.forName("javax.el.ExpressionFactory");
		final Object expressionFactoryInstance = expressionFactory.getMethod("newInstance").invoke(null);
		assertTrue(expressionFactoryInstance.getClass().getName().startsWith("com.sun.el."));

		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			final Validator validator = factory.getValidator();
			final Set<ConstraintViolation<InterpolationFixture>> violations = validator.validate(new InterpolationFixture());

			assertEquals(3, violations.size());
			assertTrue(violations.stream().anyMatch(violation -> "EL computed 6".equals(violation.getMessage())));
			assertTrue(violations.stream().anyMatch(violation -> "custom bundle message".equals(violation.getMessage())));
			assertTrue(violations.stream().anyMatch(violation -> "literal message".equals(violation.getMessage())));
		}
	}

	@Test
	public void springMvcProductionValidatorBootstrapsAndValidatesNisRequestTypes() throws Exception {
		final org.springframework.validation.Validator springValidator = new NisWebAppInitializer().mvcValidator();
		((InitializingBean) springValidator).afterPropertiesSet();

		final Validator javaxValidator = (Validator) springValidator;
		assertTrue(javaxValidator.validate(new PrivateKey(BigInteger.ONE)).isEmpty());
		assertEquals(3, javaxValidator.validate(new InterpolationFixture()).size());
	}

	private static class ValidationFixture {
		@NotBlank
		@Size(min = 3, max = 8)
		private final String value;

		private ValidationFixture(final String value) {
			this.value = value;
		}
	}

	private static class InterpolationFixture {
		@Min(value = 5, message = "EL computed ${validatedValue * 2}")
		private final int amount = 3;

		@NotBlank(message = "{validation.bundle.message}")
		private final String bundled = "";

		@NotBlank(message = "literal message")
		private final String literal = "";
	}
}
