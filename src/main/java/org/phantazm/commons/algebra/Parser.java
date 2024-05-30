package org.phantazm.commons.algebra;

import it.unimi.dsi.fastutil.ints.IntObjectPair;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class Parser {
    private enum ParseState {
        SEEK,
        NUMBER,
        STRING,
    }

    private enum TokenType {
        NUMBER,
        FUNCTION,
        VARIABLE,
        OPERATOR
    }

    private record Token(TokenType type,
        String raw,
        String functionName,
        List<Token> children) {

        public Token(TokenType type,
            String raw,
            List<Token> children) {
            this(type, raw, null, children);
        }
    }

    public final class Statement {

    }

    private static void validateParenthesis(String input) {
        int count = 0;
        for (int i = 0; i < input.length(); i++) {
            switch (input.charAt(i)) {
                case '(' -> count++;
                case ')' -> {
                    if (--count < 0) {
                        throw new RuntimeException("Unbalanced parenthesis");
                    }
                }
            }
        }

        if (count != 0) {
            throw new RuntimeException("Unbalanced parenthesis");
        }
    }

    private static boolean isVariable(String test) {
        if (test.isEmpty()) {
            return false;
        }

        if (test.length() == 1) {
            char c = test.charAt(0);
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }

        if (test.charAt(1) != '_') {
            return false;
        }

        if (test.length() == 2) {
            return false;
        }

        for (int i = 2; i < test.length(); i++) {
            char c = test.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }

        return true;
    }

    private static IntObjectPair<String> group(String input, int start) {
        int count = 1;
        for (int i = start + 1; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '(') {
                count++;
            } else if (c == ')') {
                count--;
            }

            if (count == 0) {
                if (start == i - 1) {
                    return IntObjectPair.of(i + 1, "");
                }

                return IntObjectPair.of(i + 1, input.substring(start + 1, i));
            }
        }

        throw new RuntimeException();
    }

    private static int maybePush(Token token, int i, Deque<Token> stack) {
        IntObjectPair<String> body = group(token.raw, i);

        if (!body.right().isEmpty()) {
            Token functionToken = new Token(TokenType.FUNCTION, body.right(), new ArrayList<>());
            token.children.add(functionToken);
            stack.push(functionToken);

            return body.firstInt();
        }

        return i;
    }

    public static void parse(@NotNull String input) {
        validateParenthesis(input);

        Deque<Token> stack = new ArrayDeque<>(4);

        Token root = new Token(TokenType.FUNCTION, input, new ArrayList<>());
        stack.push(root);

        while (!stack.isEmpty()) {
            Token token = stack.pop();

            StringBuilder builder = new StringBuilder();
            ParseState state = ParseState.SEEK;
            boolean foundWhitespace = false;

            for (int i = 0; i < token.raw.length(); i++) {
                char c = token.raw.charAt(i);
                boolean isWhitespace = Character.isWhitespace(c);
                boolean isOperator = c == '+' || c == '-' || c == '*' || c == '/' || c == '^' || c == ',';

                if (state == ParseState.SEEK) {
                    if (isWhitespace) {
                        continue;
                    }

                    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_') {
                        state = ParseState.STRING;
                    } else if (c >= '0' && c <= '9') {
                        state = ParseState.NUMBER;
                    } else if (isOperator) {
                        token.children.add(new Token(TokenType.OPERATOR, Character.toString(c), List.of()));
                    } else if (c == '(') {
                        i = maybePush(token, i, stack);
                    } else if (c == ')') {
                        break;
                    } else {
                        throw new RuntimeException();
                    }
                }

                switch (state) {
                    case NUMBER -> {
                        boolean validChar = (c >= '0' && c <= '9') || c == '.';
                        if (validChar) {
                            if (foundWhitespace) {
                                throw new RuntimeException();
                            }

                            builder.append(c);
                        } else if (isWhitespace) {
                            foundWhitespace = true;
                            continue;
                        }

                        if (isOperator || c == ')' || (i == token.raw.length() - 1)) {
                            String numberString = builder.toString();
                            Token number = new Token(TokenType.NUMBER, numberString, List.of());
                            token.children.add(number);
                            if (isOperator) {
                                token.children.add(new Token(TokenType.OPERATOR, Character.toString(c), List.of()));
                            }

                            builder.setLength(0);
                            foundWhitespace = false;
                            state = ParseState.SEEK;
                        } else if (isWhitespace) {
                            foundWhitespace = true;
                        } else if (!validChar) {
                            throw new RuntimeException();
                        }
                    }
                    case STRING -> {
                        boolean validChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
                        if (validChar) {
                            if (foundWhitespace) {
                                throw new RuntimeException();
                            }

                            builder.append(c);
                        } else if (isWhitespace) {
                            foundWhitespace = true;
                            continue;
                        }

                        if (isOperator || c == ')' || c == '(' || (i == token.raw.length() - 1)) {
                            String string = builder.toString();
                            boolean isVariable = isVariable(string);

                            if (c != '(' && !isVariable) {
                                throw new RuntimeException();
                            }

                            if (isVariable) {
                                Token variableToken = new Token(TokenType.VARIABLE, string, List.of());
                                token.children.add(variableToken);
                            } else {
                                // c == '('
                                i = maybePush(token, i, stack);
                            }

                            if (isOperator) {
                                token.children.add(new Token(TokenType.OPERATOR, Character.toString(c), List.of()));
                            }

                            builder.setLength(0);
                            foundWhitespace = false;
                            state = ParseState.SEEK;
                        } else if (!validChar) {
                            throw new RuntimeException();
                        }
                    }
                }
            }

            for (int i = token.children.size() - 1; i >= 0; i--) {
                Token current = token.children.get(i);
                Token previous = i == 0 ? null : token.children.get(i - 1);

                switch (current.type) {
                    case NUMBER, FUNCTION, VARIABLE -> {
                        if (previous != null && previous.type != TokenType.OPERATOR) {
                            throw new RuntimeException();
                        }
                    }
                    case OPERATOR -> {
                        if (previous == null) {

                        }
                    }
                }
            }
        }


    }
}
