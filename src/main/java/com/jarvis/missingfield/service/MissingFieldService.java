package com.jarvis.missingfield.service;

import com.jarvis.missingfield.config.MissingFieldConfig;
import com.jarvis.missingfield.dto.MissingFieldRequest;
import com.jarvis.missingfield.dto.MissingFieldResponse;
import com.jarvis.missingfield.service.validator.FieldValidator;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MissingFieldService {

  private final MissingFieldConfig config;
  private final List<FieldValidator> validators;

  public MissingFieldResponse processRequest(MissingFieldRequest request) {
    String correlationId = MDC.get("correlationId");
    log.info(
            "[{}] Processing missing field check for intent: {}",
            correlationId,
            request.getIntentType());

    String intent = request.getIntentType();
    Map<String, Object> provided =
            normalizeFields(request.getExtractedFields(), config.getSynonyms());

    Set<String> required = getDynamicRequiredFields(intent, provided);

    List<String> missing = new ArrayList<>();
    Map<String, String> prompts = new HashMap<>();
    Map<String, String> validationErrors = new HashMap<>();
    int filled = 0;

    for (String field : required) {
      Object value = provided.get(field);

      if (value != null && !value.toString().isBlank()) {
        String validationType = config.getValidations().get(field);
        if (validationType != null) {
          Optional<String> err = validateField(field, value, validationType);
          if (err.isPresent()) {
            validationErrors.put(field, err.get());
            prompts.put(field, err.get());
            continue;
          }
        }
        filled++;
      } else {
        missing.add(field);
        // prompts.put(field, getContextPrompt(field, provided, config.getPrompts()));
      }
    }

    String singlePrompt = buildSinglePrompt(required, provided, config.getPrompts());
    int total = required.size();
    int percent = (total == 0) ? 100 : (filled * 100 / total);

    log.info("[{}] Missing: {} | Validation errors: {}", correlationId, missing, validationErrors);

    return MissingFieldResponse.builder()
            .missingFields(missing)
            .fieldPrompts(Map.of("prompt", singlePrompt))
            .validationErrors(validationErrors)
            .progress(Map.of("filled", filled, "total", total, "percent", percent))
            .dialogAct(missing.isEmpty() ? "confirmation" : "re-prompt")
            .build();
  }

  // Synonym normalization
  private Map<String, Object> normalizeFields(
          Map<String, Object> original, Map<String, String> synonyms) {
    Map<String, Object> normalized = new HashMap<>();
    if (original == null) return normalized;
    original.forEach(
            (key, value) -> {
              String canonical = synonyms.getOrDefault(key.toLowerCase(), key);
              normalized.put(canonical, value);
            });
    return normalized;
  }

  // Handles required and conditional fields
  private Set<String> getDynamicRequiredFields(String intent, Map<String, Object> provided) {
    MissingFieldConfig.SchemaFields schema = config.getSchemas().get(intent);
    Set<String> result = new HashSet<>(schema.getRequired());
    if (schema.getConditional() != null) {
      schema
              .getConditional()
              .forEach(
                      (field, condition) -> {
                        String[] parts = condition.split(":");
                        if (parts.length == 2) {
                          String checkField = parts[0];
                          String checkValue = parts[1];
                          Object val = provided.get(checkField);
                          if (val != null && val.toString().equalsIgnoreCase(checkValue)) {
                            result.add(field);
                          }
                        }
                      });
    }
    return result;
  }

  // Validator dispatcher
  private Optional<String> validateField(String field, Object value, String type) {
    for (FieldValidator v : validators) {
      if (v.supports(type)) {
        return v.validate(field, value);
      }
    }
    if (type != null && type.startsWith("enum:")) {
      String[] allowed = type.split(":")[1].split(",");
      for (String option : allowed) {
        if (option.trim().equalsIgnoreCase(value.toString().trim())) {
          return Optional.empty();
        }
      }
      return Optional.of("Invalid value for " + field + ". Allowed: " + String.join(", ", allowed));
    }
    return Optional.empty();
  }

  // Context-aware prompt
  private String getContextPrompt(
          String field, Map<String, Object> provided, Map<String, String> prompts) {

    StringBuilder context = new StringBuilder();

    for (Map.Entry<String, Object> entry : provided.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();

      if (!key.equals(field) && value != null && !value.toString().isBlank()) {
        context.append(key.replace('_', ' ')).append(" is already filled. ");
      }
    }

    String prompt = prompts.getOrDefault(field, "Please provide " + field.replace('_', ' '));
    return context.append(prompt).toString();
  }

  private String buildSinglePrompt(
          Set<String> required, Map<String, Object> provided, Map<String, String> prompts) {
    List<String> filledFields = new ArrayList<>();
    List<String> missingFields = new ArrayList<>();

    for (String field : required) {
      Object value = provided.get(field);
      if (value != null && !value.toString().isBlank()) {
        filledFields.add(field.replace('_', ' '));
      } else {
        missingFields.add(field.replace('_', ' '));
      }
    }

    StringBuilder prompt = new StringBuilder();

    if (!filledFields.isEmpty()) {
      prompt.append(String.join(", ", filledFields)).append(" are already filled. ");
    }

    if (!missingFields.isEmpty()) {
      prompt
              .append("Please provide the following: ")
              .append(String.join(", ", missingFields))
              .append(".");
    }

    return prompt.toString();
  }
}