-- Hash anterior no seed não correspondia à senha documentada (Admin@123).
-- BCrypt strength 12, gerado com BCryptPasswordEncoder(12).encode("Admin@123")
UPDATE usuarios
SET senha = '$2a$12$dUWW2byZxADelFyPdO0buOqT7cj9o4hduYSRctFZ1j9pywEhpde56'
WHERE email = 'admin@oficina.com';
