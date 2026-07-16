package com.liushuwen.rag.auth.service;

import com.liushuwen.rag.auth.entity.User;

public interface UserService {

    User register(String username, String password);

    User login(String username, String password);

    User getCurrentUser();
}
