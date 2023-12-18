package aero.minova.cas.app.extension;

import org.springframework.stereotype.Component;

import aero.minova.cas.service.model.User;
import jakarta.annotation.PostConstruct;

@Component
public class UserExtension extends BaseExtension<User> {

	@PostConstruct
	public void setup() {
		super.setup(User.class);
	}
}