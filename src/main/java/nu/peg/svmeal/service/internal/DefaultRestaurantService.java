package nu.peg.svmeal.service.internal;

import static nu.peg.svmeal.config.CacheNames.RESTAURANTS;
import static nu.peg.svmeal.config.CacheNames.RESTAURANT_DTOS;
import static nu.peg.svmeal.config.CircuitBreakers.SV_SEARCH;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import nu.peg.svmeal.exceptions.ExternalException;
import nu.peg.svmeal.model.RestaurantDto;
import nu.peg.svmeal.model.SvRestaurant;
import nu.peg.svmeal.model.svsearch.RestaurantSearchResponseCallbackDto;
import nu.peg.svmeal.model.svsearch.RestaurantSearchResponseDto;
import nu.peg.svmeal.service.RestaurantService;
import nu.peg.svmeal.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.convert.ConversionService;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
public class DefaultRestaurantService implements RestaurantService {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultRestaurantService.class);
  private static final String RESTAURANT_SEARCH_URL =
      "https://www.sv-restaurant.ch/de/mitarbeiterrestaurants/restaurantsuche-mitarbeiterrestaurants?type=8700";

  private final ObjectMapper objectMapper;
  private final ConversionService conversionService;
  private final RestTemplate restTemplate;

  @Autowired
  public DefaultRestaurantService(
      ObjectMapper objectMapper, ConversionService conversionService, RestTemplate restTemplate) {
    this.objectMapper = objectMapper;
    this.conversionService = conversionService;
    this.restTemplate = restTemplate;
  }

  @Override
  @Cacheable(RESTAURANTS)
  @CircuitBreaker(name = SV_SEARCH)
  public List<SvRestaurant> getRestaurants() {
    LOGGER.info("Fetching restaurant list");
    Map<String, Object> formData = new HashMap<>();
    formData.put("searchfield", "");
    formData.put("typeofrestaurant", 0);
    formData.put("entranceregulation", 0);

    HttpEntity<MultiValueMap<String, String>> formDataEntity = HttpUtil.getPostFormData(formData);
    ResponseEntity<RestaurantSearchResponseDto> searchDtoResponse =
        restTemplate.postForEntity(
            RESTAURANT_SEARCH_URL, formDataEntity, RestaurantSearchResponseDto.class);

    String callbackFunc = searchDtoResponse.getBody().getEmpty().getCallbackfunc();
    String callbackData = callbackFunc.substring(36, callbackFunc.length() - 8);
    RestaurantSearchResponseCallbackDto searchResponseCallback;
    try {
      searchResponseCallback =
          objectMapper.readValue(callbackData, RestaurantSearchResponseCallbackDto.class);
    } catch (JsonProcessingException e) {
      throw new ExternalException("Failed to parse restaurant search response", e);
    }

    return searchResponseCallback.getList().stream()
        .filter(rest -> !rest.getLink().contains("sv-group") && !rest.getLink().isEmpty())
        .peek(DefaultRestaurantService::upgradeRestaurantLinkToHttps)
        .collect(Collectors.toList());
  }

  @Override
  @Cacheable(RESTAURANT_DTOS)
  @CircuitBreaker(name = SV_SEARCH)
  public List<RestaurantDto> getRestaurantDtos() {
    return this.getRestaurants().stream()
        .map(restaurant -> conversionService.convert(restaurant, RestaurantDto.class))
        .collect(Collectors.toList());
  }

  private static void upgradeRestaurantLinkToHttps(SvRestaurant rest) {
    try {
      URL url = new URL(rest.getLink());

      if (url.getProtocol().equals("http")) {
        rest.setLink(String.format("https%s", rest.getLink().substring(4)));
      }
    } catch (MalformedURLException ignored) {
      // ignored
    }
  }
}
