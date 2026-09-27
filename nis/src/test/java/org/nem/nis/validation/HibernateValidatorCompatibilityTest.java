package org.nem.nis.validation;

import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HibernateValidatorCompatibilityTest {
	@Test
	public void javaxBeanValidationProviderAcceptsAndRejectsTheSameConstraintContract() {
		try (ValidatorFactory factory = Validation.byDefaultProvider().configure()
				.messageInterpolator(new ParameterMessageInterpolator()).buildValidatorFactory()) {
			final Validator validator = factory.getValidator();

			assertTrue(validator.validate(new ValidationFixture("valid")).isEmpty());

			final Set<ConstraintViolation<ValidationFixture>> violations = validator.validate(new ValidationFixture(""));
			assertEquals(2, violations.size());
			assertTrue(violations.stream().allMatch(violation -> "value".equals(violation.getPropertyPath().toString())));
		}
	}

	private static class ValidationFixture {
		@NotBlank
		@Size(min = 3, max = 8)
		private final String value;

		private ValidationFixture(final String value) {
			this.value = value;
		}
	}
}
